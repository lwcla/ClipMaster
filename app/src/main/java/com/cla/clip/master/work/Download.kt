package com.cla.clip.master.work

import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.cla.clip.base.general.R
import com.cla.clip.base.general.utils.MediaStoreTarget
import com.cla.clip.base.general.utils.failure
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.showName
import com.cla.clip.base.general.utils.success
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
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
        protected fun validateMediaResponse(response: Response) {
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
        val referer: String?,
        val userAgent: String?,
        val cookie: String?,
        val mediaTarget: MediaStoreTarget,
    ) : Download() {

        override suspend fun ListenableWorker.start(progress: suspend (Int) -> Unit) = coroutineScope {
            if (!response.isSuccessful) {
                response.close()
                error("HTTP ${response.code} ${response.message}, url=${response.request.url}")
            }

            // 1) 拉入口 playlist
            val entryText = response.use {
                it.body?.string() ?: error("Empty m3u8 body")
            }

            val entryUrl = response.request.url.toString()

            // 2) master -> 选最高码率
            val mediaUrl: String
            val mediaText: String
            if (entryText.contains("#EXT-X-STREAM-INF", true)) {
                val variants = parseMasterPlaylist(entryUrl, entryText)
                val selected = variants.maxByOrNull { it.bandwidth } ?: error("master playlist 无可用码流")
                mediaUrl = selected.url
                mediaText = executeRequest(mediaUrl, referer, userAgent, cookie).use {
                    it.body?.string() ?: error("Empty media playlist")
                }
            } else {
                mediaUrl = entryUrl
                mediaText = entryText
            }

            val segments = parseMediaPlaylistWithKeys(mediaUrl, mediaText)

            // KEY 缓存
            val keyCache = ConcurrentHashMap<String, ByteArray>()
            suspend fun getKeyBytes(keyUri: String): ByteArray {
                keyCache[keyUri]?.let { return it }
                val keyBytes = executeRequest(keyUri, referer, userAgent, cookie).use { resp ->
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

            val tempDir = File(applicationContext.cacheDir, "m3u8_${taskId}_${System.currentTimeMillis()}").apply { mkdirs() }
            val semaphore = Semaphore(4)
            val done = AtomicInteger(0)

            try {
                // 3) 并发下载+解密到临时文件
                segments.mapIndexed { index, seg ->
                    async {
                        semaphore.withPermit {
                            if (isStopped) error("Worker stopped")

                            val rawBytes = executeRequest(seg.url, referer, userAgent, cookie).use { resp ->
                                resp.body?.bytes() ?: error("Empty segment body: ${seg.url}")
                            }

                            val finalBytes = if (seg.key?.method == "AES-128") {
                                val keyUri = seg.key.keyUri ?: error("AES-128 key uri is null")
                                val key = getKeyBytes(keyUri)
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

                // 4) 顺序合并
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
                throw t
            } finally {
                tempDir.listFiles()?.forEach { it.delete() }
                tempDir.delete()
            }
        }

        private fun requestBuilder(url: String, referer: String?, userAgent: String?, cookie: String?) =
            Request.Builder().url(url).apply {
                if (!referer.isNullOrBlank()) header("Referer", referer)
                if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent)
                if (!cookie.isNullOrBlank()) header("Cookie", cookie)
            }.build()

        private fun executeRequest(url: String, referer: String?, userAgent: String?, cookie: String?): Response {
            val response = okHttpClient.newCall(requestBuilder(url, referer, userAgent, cookie)).execute()
            if (!response.isSuccessful) {
                response.close()
                error("HTTP ${response.code} ${response.message}, url=$url")
            }
            return response
        }

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

        private fun parseAttrList(line: String): Map<String, String> {
            // line like: #EXT-X-KEY:METHOD=AES-128,URI="https://...",IV=0x...
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

        private fun resolveUrl(baseUrl: String, ref: String): String {
            val raw = ref.trim()
            if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
            val base = baseUrl.toHttpUrlOrNull() ?: error("Invalid base url: $baseUrl")
            return base.resolve(raw)?.toString() ?: error("Cannot resolve url: $raw")
        }

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

        private fun defaultIvForSequence(sequence: Long): ByteArray {
            // 16-byte big-endian，低 8 byte 放 sequence
            val iv = ByteArray(16)
            val bb = ByteBuffer.wrap(iv).order(ByteOrder.BIG_ENDIAN)
            bb.position(8)
            bb.putLong(sequence)
            return iv
        }

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

        private data class MasterVariant(val bandwidth: Long, val url: String)

        private data class SegmentItem(
            val sequence: Long,
            val url: String,           // 绝对 URL
            val key: SegmentKey? = null
        )

        private data class SegmentKey(
            val method: String,    // NONE / AES-128 / SAMPLE-AES
            val keyUri: String?,   // 绝对 URL
            val iv: ByteArray?     // 16 bytes or null
        )
    }

}