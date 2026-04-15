package com.cla.clip.master.work

import androidx.work.ListenableWorker
import com.cla.clip.base.general.utils.MediaStoreTarget
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

sealed class Download {

    abstract suspend fun ListenableWorker.start(progress: suspend (Int) -> Unit)


    data class Video(val response: Response, val fileName: String, val mediaTarget: MediaStoreTarget) : Download() {

        companion object {
            private const val TAG = "Download:Video"
        }

        override suspend fun ListenableWorker.start(progress: suspend (Int) -> Unit) {
            validateMediaResponse(response)

            response.use { response ->
                val body = response.body ?: throw IllegalStateException("Empty response body")
                val totalSize = body.contentLength()

                val (_, filePath, outputStream) = mediaTarget
                logD(TAG) { "$fileName 开始下载 total=$totalSize path=$filePath" }

                body.byteStream().use { input ->
                    outputStream.use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var read: Int
                        var lastProgress = -1

                        while (input.read(buffer).also { read = it } != -1) {
                            if (isStopped) throw IllegalStateException("Worker stopped")

                            output.write(buffer, 0, read)
                            downloaded += read

                            val curProgress = if (totalSize > 0L) {
                                ((downloaded * 100) / totalSize).toInt().coerceIn(0, 100)
                            } else 0

                            if (curProgress != lastProgress) {
                                lastProgress = curProgress
                                progress(curProgress)
                            }
                        }
                    }
                }
            }
        }

        /**
         * 视频地址出错的情况下，返回了一个json {"status_code":0,"status_msg":"url doesn't match"}
         * 这个时候不能只依靠response.isSuccessful去判断是否能够下载失败
         */
        private fun validateMediaResponse(response: Response) {
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} ${response.message}")
            }

            val contentType = response.header("Content-Type").orEmpty().lowercase()

            val badTypes = listOf("application/json", "text/json", "text/html")
            if (badTypes.any { contentType.contains(it) }) {
                throw IllegalStateException("Unexpected content-type: $contentType")
            }

            val allowByType = contentType.startsWith("video/") ||
                    contentType.contains("application/octet-stream") ||
                    contentType.contains("application/vnd.apple.mpegurl")

            // peek 不会消耗真正的 body 流
            val peek = response.peekBody(4096).bytes()
            val textHead = peek.toString(Charsets.UTF_8).trimStart().lowercase()

            val looksLikeJsonOrHtml = textHead.startsWith("{") ||
                    textHead.startsWith("[") ||
                    textHead.startsWith("<!doctype") ||
                    textHead.startsWith("<html")

            if (looksLikeJsonOrHtml) {
                throw IllegalStateException("Body is not media stream")
            }

            val looksLikeMp4 = peek.size > 12 &&
                    String(peek.copyOfRange(4, 8), Charsets.US_ASCII) == "ftyp"
            val looksLikeWebm = peek.size >= 4 &&
                    peek[0] == 0x1A.toByte() &&
                    peek[1] == 0x45.toByte() &&
                    peek[2] == 0xDF.toByte() &&
                    peek[3] == 0xA3.toByte()
            val looksLikeFlv = peek.size >= 3 &&
                    peek[0] == 'F'.code.toByte() &&
                    peek[1] == 'L'.code.toByte() &&
                    peek[2] == 'V'.code.toByte()

            val allowBySniff = looksLikeMp4 || looksLikeWebm || looksLikeFlv

            if (!allowByType && !allowBySniff) {
                throw IllegalStateException("Response is not recognized as media")
            }
        }
    }


    data class M3u8(
        val taskId: Long,
        val response: Response,
        val mediaTarget: MediaStoreTarget,
        val newResponse: (url: String) -> Response,
    ) : Download() {

        companion object {
            private const val TAG = "Download:M3u8"
        }

        /**
         * M3U8 下载入口。
         *
         * 整体流程：
         * 1. 校验入口响应并读取入口 playlist 文本。
         * 2. 如果是 master playlist（包含多码率），选择最高码率的子 playlist。
         * 3. 解析子 playlist，得到分片清单（Segment）以及每个分片对应的加密信息（如 AES-128 KEY）。
         * 4. 并发下载分片；遇到 AES-128 时先拉取 KEY 并解密分片。
         * 5. 按分片顺序合并成单个输出文件流，最终写入 MediaStore 目标。
         *
         * 说明：
         * - 这里使用 `coroutineScope` 让所有并发任务受结构化并发约束，任一分片失败会向外抛错并取消同级任务。
         * - `progress` 回调参数保留用于上层汇报进度；当前代码里尚未实际调用该回调更新 UI。
         * - 方法仅负责下载/解密/合并，不负责持久化任务状态（相关代码在当前文件中已注释）。
         */
        override suspend fun ListenableWorker.start(progress: suspend (Int) -> Unit) = coroutineScope {
            // 入口 URL 必须先请求成功；失败时及时关闭 Response，避免连接泄漏。
            if (!response.isSuccessful) {
                response.close()
                error("HTTP ${response.code} ${response.message}, url=${response.request.url}")
            }

            // 1) 读取入口 playlist 内容（可能是 master，也可能直接就是 media playlist）
            val entryText = response.use {
                it.body?.string() ?: error("Empty m3u8 body")
            }

            val entryUrl = response.request.url.toString()

            // 2) 如果入口是 master playlist，则选择最高带宽码流对应的子 playlist
            val mediaUrl: String
            val mediaText: String
            if (entryText.contains("#EXT-X-STREAM-INF", true)) {
                val variants = parseMasterPlaylist(entryUrl, entryText)
                val selected = variants.maxByOrNull { it.bandwidth } ?: error("master playlist 无可用码流")
                mediaUrl = selected.url
                mediaText = newResponse(mediaUrl).use {
                    it.body?.string() ?: error("Empty media playlist")
                }
            } else {
                // 入口已经是可下载分片列表
                mediaUrl = entryUrl
                mediaText = entryText
            }

            // 解析分片与加密信息（KEY 会继承直到遇到下一条 EXT-X-KEY）
            val segments = parseMediaPlaylistWithKeys(mediaUrl, mediaText)

            // KEY 缓存：同一 keyUri 只拉取一次，避免每个分片重复下载 KEY。
            val keyCache = ConcurrentHashMap<String, ByteArray>()

            /**
             * 按需拉取 AES-128 的密钥内容并做标准化。
             *
             * - HLS AES-128 标准密钥长度为 16 字节。
             * - 部分服务端返回异常长度时，这里按现有逻辑做兼容处理：
             *   - ==16：直接使用
             *   - >16：截取前 16 字节
             *   - <16：视为错误
             */
            suspend fun getKeyBytes(keyUri: String): ByteArray {
                keyCache[keyUri]?.let { return it }
                val keyBytes = newResponse(keyUri).use { resp ->
                    resp.body?.bytes() ?: error("Empty key body: $keyUri")
                }
                val normalized = when {
                    keyBytes.size == 16 -> keyBytes
                    keyBytes.size > 16 -> keyBytes.copyOfRange(0, 16)
                    else -> error("Invalid AES-128 key length=${keyBytes.size}, uri=$keyUri")
                }
                keyCache[keyUri] = normalized
                return normalized
            }

            // 分片先落地到临时目录，全部完成后再按顺序合并到最终文件。
            val tempDir = File(applicationContext.cacheDir, "m3u8_${taskId}_${System.currentTimeMillis()}").apply { mkdirs() }
            val semaphore = Semaphore(4) // 设置并发数量，避免对网络/设备造成过大压力。
            val done = AtomicInteger(0)

            try {
                // 3) 并发下载分片，并在需要时进行 AES-128 解密，然后写入临时文件
                segments.mapIndexed { index, seg ->
                    async {
                        semaphore.withPermit {
                            if (isStopped) error("Worker stopped")

                            // 分片原始字节（可能是明文，也可能是加密密文）
                            val rawBytes = newResponse(seg.url).use { resp ->
                                resp.body?.bytes() ?: error("Empty segment body: ${seg.url}")
                            }

                            // 分片级解密：仅在当前分片声明 AES-128 时执行。
                            val finalBytes = if (seg.key?.method == "AES-128") {
                                val keyUri = seg.key.keyUri ?: error("AES-128 key uri is null")
                                val key = getKeyBytes(keyUri)
                                // 若 playlist 未显式给 IV，则使用 sequence 构造默认 IV（HLS 规则）。
                                val iv = seg.key.iv ?: defaultIvForSequence(seg.sequence)
                                decryptAes128(rawBytes, key, iv)
                            } else {
                                rawBytes
                            }

                            val segFile = File(tempDir, "seg_${index.toString().padStart(6, '0')}.ts")
                            segFile.outputStream().use { it.write(finalBytes) }

                            val finished = done.incrementAndGet()
                            val curProgress = ((finished * 90L) / segments.size).toInt().coerceIn(0, 90)

//                            downloadRepo.updateProgress(taskId, progress)
//                            setProgress(workDataOf("progress" to progress))
//                            setForeground(
//                                buildForegroundInfo(
//                                    applicationContext.getString(R.string.base_general_download_now),
//                                    fileName.showName,
//                                    progress
//                                )
//                            )

                            segFile
                        }
                    }
                }.awaitAll()

                // 4) 顺序合并：必须按分片索引顺序写入，否则会造成视频时序错乱。
                val (_, filePath, outputStream) = mediaTarget
                outputStream.use { out ->
                    segments.indices.forEach { i ->
                        val segFile = File(tempDir, "seg_${i.toString().padStart(6, '0')}.ts")
                        segFile.inputStream().use { it.copyTo(out) }

                        val curProgress = (90 + ((i + 1) * 10 / segments.size)).coerceIn(90, 100)
//                        downloadRepo.updateProgress(taskId, progress)
//                        setProgress(workDataOf("progress" to progress))
//                        setForeground(
//                            buildForegroundInfo(
//                                applicationContext.getString(R.string.base_general_download_now),
//                                fileName.showName,
//                                progress
//                            )
//                        )
                    }
                }

//                saveVideo.success(applicationContext, mediaTarget)
//                downloadRepo.markSuccess(taskId, filePath)
//                notificationHelper.notifyDownloadResult(
//                    taskId,
//                    title = applicationContext.getString(R.string.base_general_download_completed),
//                    fileName = fileName.showName,
//                    content = filePath,
//                )
            } catch (t: Throwable) {
                logE(TAG, t) { "下载失败: ${t.message}" }
                throw t
            } finally {
                // 无论成功或失败都清理临时目录，避免缓存目录持续膨胀。
                tempDir.listFiles()?.forEach { it.delete() }
                tempDir.delete()
            }
        }

        /**
         * 解析 master playlist，提取每个码率分支（variant）。
         *
         * 规则：
         * - 读取 `#EXT-X-STREAM-INF` 行上的属性，主要关心 `BANDWIDTH`。
         * - 其后第一条非注释行即子 playlist URL（可能是相对路径）。
         * - 最终统一转为绝对 URL，供后续拉取子 playlist 使用。
         */
        private fun parseMasterPlaylist(baseUrl: String, content: String): List<MasterVariant> {
            val lines = content.lines()
            val variants = mutableListOf<MasterVariant>()
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF", true)) {
                    val attrs = parseAttrList(line)
                    val bw = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L
                    var j = i + 1
                    while (j < lines.size) {
                        val u = lines[j].trim()
                        if (u.isNotEmpty() && !u.startsWith("#")) {
                            variants += MasterVariant(bw, resolveUrl(baseUrl, u))
                            break
                        }
                        j++
                    }
                    i = j
                }
                i++
            }
            return variants
        }

        /**
         * 解析 media playlist，生成带 KEY 信息的分片列表。
         *
         * 行为说明：
         * - `#EXT-X-MEDIA-SEQUENCE` 决定起始 sequence，用于默认 IV 推导。
         * - `#EXT-X-KEY` 会更新“当前生效的加密参数”，并作用于后续分片，直到下一条 KEY 出现。
         * - 非注释行视为分片 URL（相对路径会解析成绝对路径）。
         * - 若最终没有任何分片，直接报错。
         */
        private fun parseMediaPlaylistWithKeys(baseUrl: String, content: String): List<SegmentItem> {
            val lines = content.lines()

            var mediaSequence = 0L
            lines.firstOrNull { it.trim().startsWith("#EXT-X-MEDIA-SEQUENCE", true) }?.let { line ->
                mediaSequence = line.substringAfter(":").trim().toLongOrNull() ?: 0L
            }

            var seq = mediaSequence
            var currentKey: SegmentKey? = null
            val result = mutableListOf<SegmentItem>()

            lines.forEach { raw ->
                val line = raw.trim()
                if (line.isBlank()) return@forEach

                if (line.startsWith("#EXT-X-KEY", true)) {
                    val attrs = parseAttrList(line)
                    val method = attrs["METHOD"]?.uppercase() ?: "NONE"

                    when (method) {
                        "NONE" -> currentKey = null
                        "AES-128" -> {
                            val keyUriRaw = attrs["URI"] ?: error("AES-128 KEY 缺少 URI")
                            val keyUriAbs = resolveUrl(baseUrl, keyUriRaw)
                            val iv = parseHexIv(attrs["IV"])
                            currentKey = SegmentKey(method = method, keyUri = keyUriAbs, iv = iv)
                        }

                        else -> error("不支持的加密方式: $method")
                    }
                    return@forEach
                }

                if (!line.startsWith("#")) {
                    result += SegmentItem(
                        sequence = seq,
                        url = resolveUrl(baseUrl, line),
                        key = currentKey
                    )
                    seq++
                }
            }

            if (result.isEmpty()) error("m3u8 未解析到分片")
            return result
        }

        /**
         * 解析 M3U8 attribute list（`k=v,k2="v2"`）格式。
         *
         * 示例：
         * `#EXT-X-KEY:METHOD=AES-128,URI="https://a.com/key",IV=0x...`
         *
         * 返回值是键值对 Map，便于按属性名读取。
         */
        private fun parseAttrList(line: String): Map<String, String> {
            val idx = line.indexOf(':')
            val body = if (idx >= 0) line.substring(idx + 1) else line
            val regex = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
            return regex.findAll(body).associate { m ->
                val k = m.groupValues[1]
                var v = m.groupValues[2]
                if (v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) v = v.substring(1, v.length - 1)
                k to v
            }
        }

        /**
         * 将分片/KEY 的相对地址解析为绝对地址。
         *
         * - 若 ref 已经是 http/https 绝对地址，直接返回。
         * - 否则使用 baseUrl 进行 URL 解析（处理相对路径、父路径、查询参数等）。
         */
        private fun resolveUrl(baseUrl: String, ref: String): String {
            val raw = ref.trim()
            if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
            val base = baseUrl.toHttpUrlOrNull() ?: error("Invalid base url: $baseUrl")
            return base.resolve(raw)?.toString() ?: error("Cannot resolve url: $raw")
        }

        /**
         * 解析 `IV=0x...` 文本为 16 字节数组。
         *
         * 兼容策略：
         * - 长度不足 16：左侧补零到 16 字节。
         * - 长度超过 16：取最后 16 字节。
         * - 为空：返回 null，后续会走 sequence 默认 IV 规则。
         */
        private fun parseHexIv(ivText: String?): ByteArray? {
            if (ivText.isNullOrBlank()) return null
            val hex = ivText.removePrefix("0x").removePrefix("0X")
            if (hex.isBlank()) return null
            val full = if (hex.length % 2 == 0) hex else "0$hex"
            val raw = ByteArray(full.length / 2)
            for (i in raw.indices) raw[i] = full.substring(i * 2, i * 2 + 2).toInt(16).toByte()

            // HLS 需要 16 bytes，短则左侧补零，长则取最后16字节
            return when {
                raw.size == 16 -> raw
                raw.size < 16 -> ByteArray(16 - raw.size) + raw
                else -> raw.copyOfRange(raw.size - 16, raw.size)
            }
        }

        /**
         * 根据分片序号生成默认 IV（当 EXT-X-KEY 未显式声明 IV 时使用）。
         *
         * HLS 约定：
         * - 使用 16 字节大端序。
         * - 高 8 字节补零，低 8 字节写入分片 sequence。
         */
        private fun defaultIvForSequence(sequence: Long): ByteArray {
            val iv = ByteArray(16)
            val bb = ByteBuffer.wrap(iv).order(ByteOrder.BIG_ENDIAN)
            bb.position(8)
            bb.putLong(sequence)
            return iv
        }

        /**
         * 执行 AES-128 CBC 解密。
         *
         * 说明：
         * - 首先尝试 `PKCS5Padding`，若失败再尝试 `NoPadding`。
         * - 该双分支是为了兼容不同源站对分片填充策略的差异。
         * - 仅在 `seg.key.method == AES-128` 时被调用。
         */
        private fun decryptAes128(segmentEncrypted: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
            if (key.size != 16) error("AES-128 key length must be 16, actual=${key.size}")
            val secretKey = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)

            return try {
                Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                    init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
                    doFinal(segmentEncrypted)
                }
            } catch (_: Throwable) {
                // 有些源可能是无填充
                Cipher.getInstance("AES/CBC/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
                    doFinal(segmentEncrypted)
                }
            }
        }

        /** master playlist 的码率分支信息。 */
        private data class MasterVariant(val bandwidth: Long, val url: String)

        /** 单个媒体分片信息：包含分片序号、URL 以及该分片对应的加密配置。 */
        private data class SegmentItem(
            val sequence: Long,
            val url: String,           // 绝对 URL
            val key: SegmentKey? = null
        )

        /** 分片加密配置：当前支持 NONE 与 AES-128。 */
        private data class SegmentKey(
            val method: String,    // NONE / AES-128 / SAMPLE-AES
            val keyUri: String?,   // 绝对 URL
            val iv: ByteArray?     // 16 bytes or null
        )
    }
}
