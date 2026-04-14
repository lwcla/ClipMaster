package com.cla.clip.master.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cla.clip.base.general.R
import com.cla.clip.base.general.entity.DownloadRepository
import com.cla.clip.base.general.utils.MediaStoreTarget
import com.cla.clip.base.general.utils.SaveToFile
import com.cla.clip.base.general.utils.createPath
import com.cla.clip.base.general.utils.failure
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.showName
import com.cla.clip.base.general.utils.success
import com.cla.clip.master.utils.NotificationHelper
import com.cla.clip.master.work.DownloadVideoWorker.Companion.TASK_TAG
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@HiltWorker
class DownloadVideoWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val okHttpClient: OkHttpClient,
    private val downloadRepo: DownloadRepository,
    private val notificationHelper: NotificationHelper,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "DownloadVideoWorker"

        const val TASK_TAG = "download"

        const val KEY_TASK_ID = "key_task_id"

        private const val DOU_YIN_PLAYVM = "/playwm/"
        private const val DOU_YIN_PLAY = "/play/"
    }

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK_ID, -1)
        val task = downloadRepo.getTask(taskId) ?: return Result.failure()
        val videoUrl = task.videoUrl
        val referer = task.referer
        val userAgent = task.userAgent
        val cookie = task.cookie
        val fileName = task.fileName

        logD(TAG) { "doWork: 开始下载任务 taskId=$taskId task=$task" }

        // 首帧前台通知，避免后台限制
        setForeground(buildForegroundInfo(applicationContext.getString(R.string.base_general_initialize_download), fileName.showName, 0))

        val saveVideo = SaveToFile.Video(fileName)
        val mediaTarget = saveVideo.createPath(applicationContext)

        return runCatching {
            downloadVideo(taskId, videoUrl, fileName, referer, userAgent, cookie, saveVideo, mediaTarget)
            Result.success()
        }.getOrElse { tr ->
            logE(TAG, tr) { "doWork: 下载失败" }
            downloadRepo.markFailed(taskId, tr.message ?: "Unknown error")
            notificationHelper.notifyDownloadResult(
                taskId,
                title = applicationContext.getString(R.string.base_general_download_failed),
                fileName = fileName.showName,
                content = tr.message ?: "Unknown error",
            )
            saveVideo.failure(applicationContext, mediaTarget)
            Result.failure()
        }
    }

    private suspend fun downloadVideo(
        taskId: Long,
        videoUrl: String,
        fileName: String,
        referer: String?,
        userAgent: String?,
        cookie: String?,
        saveVideo: SaveToFile,
        mediaTarget: MediaStoreTarget
    ) {
//        fun openInitialResponse(): Response {
//            return if (videoUrl.contains(DOU_YIN_PLAYVM)) {
//                runCatching {
//                    val newUrl = videoUrl.replace(DOU_YIN_PLAYVM, DOU_YIN_PLAY)
//                    executeRequest(newUrl, referer, userAgent, cookie)
//                }.getOrElse {
//                    executeRequest(videoUrl, referer, userAgent, cookie)
//                }
//            } else {
//                executeRequest(videoUrl, referer, userAgent, cookie)
//            }
//        }
//
//        openInitialResponse().use { response ->
//            val contentType = response.header("Content-Type").orEmpty().lowercase()
//            val headText = response.peekBody(128 * 1024).string()
//            val byType = contentType.contains("mpegurl") || contentType.contains("x-mpegurl")
//            val byBody = looksLikeM3u8(headText)
//
//            if (byType || byBody) {
//                // 用最终重定向后的 URL 当作 playlist 入口
//                val playlistUrl = response.request.url.toString()
//                downloadM3u8VideoWithDecrypt(
//                    taskId = taskId,
//                    entryUrl = playlistUrl,
//                    fileName = fileName,
//                    referer = referer,
//                    userAgent = userAgent,
//                    cookie = cookie,
//                    saveVideo = saveVideo,
//                    mediaTarget = mediaTarget
//                )
//                return
//            }
//
//            // 非 m3u8，走你原有逻辑
//            validateMediaResponse(response)
//
//            val body = response.body ?: throw IllegalStateException("Empty response body")
//            val totalSize = body.contentLength()
//            val (_, filePath, outputStream) = mediaTarget
//
//            body.byteStream().use { input ->
//                outputStream.use { output ->
//                    val buffer = ByteArray(8192)
//                    var downloaded = 0L
//                    var read: Int
//                    var lastProgress = -1
//
//                    while (input.read(buffer).also { read = it } != -1) {
//                        if (isStopped) throw IllegalStateException("Worker stopped")
//                        output.write(buffer, 0, read)
//                        downloaded += read
//
//                        val progress = if (totalSize > 0L) {
//                            ((downloaded * 100) / totalSize).toInt().coerceIn(0, 100)
//                        } else 0
//
//                        if (progress != lastProgress) {
//                            lastProgress = progress
//                            downloadRepo.updateProgress(taskId, progress)
//                            setProgress(workDataOf("progress" to progress))
//                            setForeground(
//                                buildForegroundInfo(
//                                    applicationContext.getString(R.string.base_general_download_now),
//                                    fileName.showName,
//                                    progress
//                                )
//                            )
//                        }
//                    }
//                }
//            }
//
//            saveVideo.success(applicationContext, mediaTarget)
//            downloadRepo.markSuccess(taskId, filePath)
//            notificationHelper.notifyDownloadResult(
//                taskId = taskId,
//                title = applicationContext.getString(R.string.base_general_download_completed),
//                fileName = fileName.showName,
//                content = filePath,
//            )
//        }

        suspend fun start(response: Response) {
            val download = if (isM3u8(response)) {
                Download.M3u8(taskId, response, referer, userAgent, cookie, mediaTarget)
            } else {
                Download.Video(response, fileName, mediaTarget)
            }

            download.apply { start { progress -> updateProgress(taskId, fileName, progress) } }
        }

        val isDouYinVm = videoUrl.contains(DOU_YIN_PLAYVM)
        if (isDouYinVm) {
            runCatching {
                logD(TAG) { "downloadVideo: 抖音尝试下载无水印的地址" }
                val newUrl = videoUrl.replace(DOU_YIN_PLAYVM, DOU_YIN_PLAY)
                val response = executeRequest(newUrl, referer, userAgent, cookie)
                start(response)
            }.getOrElse {
                logE(TAG, it) { "downloadVideo: 抖音无水印地址连接失败，换回原地址" }
                val response = executeRequest(videoUrl, referer, userAgent, cookie)
                start(response)
            }
        } else {
            val response = executeRequest(videoUrl, referer, userAgent, cookie)
            start(response)
        }

        saveVideo.success(applicationContext, mediaTarget)
        val savePath = mediaTarget.path
        downloadRepo.markSuccess(taskId, savePath)
        logI(TAG) { "下载完成 taskId=$taskId path=${savePath}" }

        notificationHelper.notifyDownloadResult(
            taskId,
            title = applicationContext.getString(R.string.base_general_download_completed),
            fileName = fileName.showName,
            content = savePath,
        )

//        fun call(url: String): Response {
//            val request = Request.Builder()
//                .url(url)
//                .apply {
//                    if (!referer.isNullOrBlank()) header("Referer", referer)
//                    if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent)
//                    if (!cookie.isNullOrBlank()) header("Cookie", cookie)
//                }
//                .build()
//
//            val response = okHttpClient.newCall(request).execute()
//
//            try {
//                if (!response.isSuccessful) {
//                    throw IllegalStateException("HTTP ${response.code} ${response.message}")
//                }
//                validateMediaResponse(response) // 这里抛错也会进 catch 关闭
//                return response
//            } catch (t: Throwable) {
//                logE(TAG, t) { "call: url=$url" }
//                response.close()
//                throw t
//            }
//        }
//
//        val response = if (videoUrl.contains(DOU_YIN_PLAYVM)) {
//            runCatching {
//                // 抖音先尝试无水印的地址，如果失败换回原地址
//                val newUrl = videoUrl.replace(DOU_YIN_PLAYVM, DOU_YIN_PLAY)
//                logD(TAG) { "downloadVideo: 抖音尝试下载无水印的地址" }
//                call(newUrl)
//            }.getOrElse {
//                logE(TAG, it) { "downloadVideo: 抖音无水印地址连接失败，换回原地址" }
//                call(videoUrl)
//            }
//        } else {
//            call(videoUrl)
//        }
//
//        response.use { response ->
//            val body = response.body ?: throw IllegalStateException("Empty response body")
//            val totalSize = body.contentLength()
//
//            val (_, filePath, outputStream) = mediaTarget
//            logD(TAG) { "$fileName 开始下载 total=$totalSize path=$filePath" }
//
//            body.byteStream().use { input ->
//                outputStream.use { output ->
//                    val buffer = ByteArray(8192)
//                    var downloaded = 0L
//                    var read: Int
//                    var lastProgress = -1
//
//                    while (input.read(buffer).also { read = it } != -1) {
//                        if (isStopped) throw IllegalStateException("Worker stopped")
//
//                        output.write(buffer, 0, read)
//                        downloaded += read
//
//                        val progress = if (totalSize > 0L) {
//                            ((downloaded * 100) / totalSize).toInt().coerceIn(0, 100)
//                        } else 0
//
//                        if (progress != lastProgress) {
//                            lastProgress = progress
//                            downloadRepo.updateProgress(taskId, progress)
//                            setProgress(workDataOf("progress" to progress))
//                            setForeground(buildForegroundInfo(applicationContext.getString(R.string.base_general_download_now), fileName.showName, progress))
//                        }
//                    }
//                }
//            }
//
//            saveVideo.success(applicationContext, mediaTarget)
//            downloadRepo.markSuccess(taskId, filePath)
//            logI(TAG) { "下载完成 taskId=$taskId path=$filePath" }
//
//            notificationHelper.notifyDownloadResult(
//                taskId,
//                title = applicationContext.getString(R.string.base_general_download_completed),
//                fileName = fileName.showName,
//                content = filePath,
//            )
//        }
    }

    private suspend fun updateProgress(taskId: Long, fileName: String, progress: Int) {
        downloadRepo.updateProgress(taskId, progress)
        setProgress(workDataOf("progress" to progress))
        setForeground(buildForegroundInfo(applicationContext.getString(R.string.base_general_download_now), fileName.showName, progress))
    }

    private fun isM3u8(response: Response): Boolean {
        val contentType = response.header("Content-Type").orEmpty().lowercase()
        val byType = contentType.contains("mpegurl") || contentType.contains("x-mpegurl")
        if (byType) {
            return true
        }

        val headText = response.peekBody(128 * 1024).string()
        return looksLikeM3u8(headText)
    }

    private fun looksLikeM3u8(text: String): Boolean {
        val t = text.trim()
        if (!t.contains("#EXTM3U", ignoreCase = true)) return false
        return t.contains("#EXTINF", true) || t.contains("#EXT-X-STREAM-INF", true)
    }

    private fun executeRequest(url: String, referer: String?, userAgent: String?, cookie: String?): Response {
        val response = okHttpClient.newCall(requestBuilder(url, referer, userAgent, cookie)).execute()
        if (!response.isSuccessful) {
            response.close()
            error("HTTP ${response.code} ${response.message}, url=$url")
        }
        return response
    }

    private fun requestBuilder(
        url: String,
        referer: String?,
        userAgent: String?,
        cookie: String?
    ) = Request.Builder().url(url).apply {
        if (!referer.isNullOrBlank()) header("Referer", referer)
        if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent)
        if (!cookie.isNullOrBlank()) header("Cookie", cookie)
    }.build()

    private fun buildForegroundInfo(title: String, fileName: String, progress: Int): ForegroundInfo {
        val notification = notificationHelper.buildDownloadNotification(title, fileName, progress)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NotificationHelper.VIDEO_DOWNLOAD_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NotificationHelper.VIDEO_DOWNLOAD_NOTIFICATION_ID, notification)
        }
    }

//    /**
//     * 视频地址出错的情况下，返回了一个json {"status_code":0,"status_msg":"url doesn't match"}
//     * 这个时候不能只依靠response.isSuccessful去判断是否能够下载失败
//     */
//    private fun validateMediaResponse(response: Response) {
//        if (!response.isSuccessful) {
//            response.close()
//            throw IllegalStateException("HTTP ${response.code} ${response.message}")
//        }
//
//        val contentType = response.header("Content-Type").orEmpty().lowercase()
//
//        val badTypes = listOf("application/json", "text/json", "text/html")
//        if (badTypes.any { contentType.contains(it) }) {
//            throw IllegalStateException("Unexpected content-type: $contentType")
//        }
//
//        val allowByType = contentType.startsWith("video/") ||
//                contentType.contains("application/octet-stream") ||
//                contentType.contains("application/vnd.apple.mpegurl")
//
//        // peek 不会消耗真正的 body 流
//        val peek = response.peekBody(4096).bytes()
//        val textHead = peek.toString(Charsets.UTF_8).trimStart().lowercase()
//
//        val looksLikeJsonOrHtml = textHead.startsWith("{") ||
//                textHead.startsWith("[") ||
//                textHead.startsWith("<!doctype") ||
//                textHead.startsWith("<html")
//
//        if (looksLikeJsonOrHtml) {
//            response.close()
//            throw IllegalStateException("Body is not media stream")
//        }
//
//        val looksLikeMp4 = peek.size > 12 &&
//                String(peek.copyOfRange(4, 8), Charsets.US_ASCII) == "ftyp"
//        val looksLikeWebm = peek.size >= 4 &&
//                peek[0] == 0x1A.toByte() &&
//                peek[1] == 0x45.toByte() &&
//                peek[2] == 0xDF.toByte() &&
//                peek[3] == 0xA3.toByte()
//        val looksLikeFlv = peek.size >= 3 &&
//                peek[0] == 'F'.code.toByte() &&
//                peek[1] == 'L'.code.toByte() &&
//                peek[2] == 'V'.code.toByte()
//
//        val allowBySniff = looksLikeMp4 || looksLikeWebm || looksLikeFlv
//
//        if (!allowByType && !allowBySniff) {
//            response.close()
//            throw IllegalStateException("Response is not recognized as media")
//        }
//    }
//
//    private data class MasterVariant(val bandwidth: Long, val url: String)
//
//    private data class SegmentKey(
//        val method: String,    // NONE / AES-128 / SAMPLE-AES
//        val keyUri: String?,   // 绝对 URL
//        val iv: ByteArray?     // 16 bytes or null
//    )
//
//    private data class SegmentItem(
//        val sequence: Long,
//        val url: String,           // 绝对 URL
//        val key: SegmentKey? = null
//    )
//

//
//    private fun resolveUrl(baseUrl: String, ref: String): String {
//        val raw = ref.trim()
//        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
//        val base = baseUrl.toHttpUrlOrNull() ?: error("Invalid base url: $baseUrl")
//        return base.resolve(raw)?.toString() ?: error("Cannot resolve url: $raw")
//    }
//


//
//    private fun parseAttrList(line: String): Map<String, String> {
//        // line like: #EXT-X-KEY:METHOD=AES-128,URI="https://...",IV=0x...
//        val idx = line.indexOf(':')
//        val body = if (idx >= 0) line.substring(idx + 1) else line
//        val regex = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
//        return regex.findAll(body).associate { m ->
//            val k = m.groupValues[1]
//            var v = m.groupValues[2]
//            if (v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) v = v.substring(1, v.length - 1)
//            k to v
//        }
//    }
//
//    private fun parseHexIv(ivText: String?): ByteArray? {
//        if (ivText.isNullOrBlank()) return null
//        val hex = ivText.removePrefix("0x").removePrefix("0X")
//        if (hex.isBlank()) return null
//        val full = if (hex.length % 2 == 0) hex else "0$hex"
//        val raw = ByteArray(full.length / 2)
//        for (i in raw.indices) raw[i] = full.substring(i * 2, i * 2 + 2).toInt(16).toByte()
//
//        // HLS 需要 16 bytes，短则左侧补零，长则取最后16字节
//        return when {
//            raw.size == 16 -> raw
//            raw.size < 16 -> ByteArray(16 - raw.size) + raw
//            else -> raw.copyOfRange(raw.size - 16, raw.size)
//        }
//    }
//
//    private fun defaultIvForSequence(sequence: Long): ByteArray {
//        // 16-byte big-endian，低 8 byte 放 sequence
//        val iv = ByteArray(16)
//        val bb = ByteBuffer.wrap(iv).order(ByteOrder.BIG_ENDIAN)
//        bb.position(8)
//        bb.putLong(sequence)
//        return iv
//    }
//
//    private fun parseMasterPlaylist(baseUrl: String, content: String): List<MasterVariant> {
//        val lines = content.lines()
//        val variants = mutableListOf<MasterVariant>()
//        var i = 0
//        while (i < lines.size) {
//            val line = lines[i].trim()
//            if (line.startsWith("#EXT-X-STREAM-INF", true)) {
//                val attrs = parseAttrList(line)
//                val bw = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L
//                var j = i + 1
//                while (j < lines.size) {
//                    val u = lines[j].trim()
//                    if (u.isNotEmpty() && !u.startsWith("#")) {
//                        variants += MasterVariant(bw, resolveUrl(baseUrl, u))
//                        break
//                    }
//                    j++
//                }
//                i = j
//            }
//            i++
//        }
//        return variants
//    }
//
//    private fun parseMediaPlaylistWithKeys(baseUrl: String, content: String): List<SegmentItem> {
//        val lines = content.lines()
//
//        var mediaSequence = 0L
//        lines.firstOrNull { it.trim().startsWith("#EXT-X-MEDIA-SEQUENCE", true) }?.let { line ->
//            mediaSequence = line.substringAfter(":").trim().toLongOrNull() ?: 0L
//        }
//
//        var seq = mediaSequence
//        var currentKey: SegmentKey? = null
//        val result = mutableListOf<SegmentItem>()
//
//        lines.forEach { raw ->
//            val line = raw.trim()
//            if (line.isBlank()) return@forEach
//
//            if (line.startsWith("#EXT-X-KEY", true)) {
//                val attrs = parseAttrList(line)
//                val method = attrs["METHOD"]?.uppercase() ?: "NONE"
//
//                when (method) {
//                    "NONE" -> currentKey = null
//                    "AES-128" -> {
//                        val keyUriRaw = attrs["URI"] ?: error("AES-128 KEY 缺少 URI")
//                        val keyUriAbs = resolveUrl(baseUrl, keyUriRaw)
//                        val iv = parseHexIv(attrs["IV"])
//                        currentKey = SegmentKey(method = method, keyUri = keyUriAbs, iv = iv)
//                    }
//
//                    else -> error("不支持的加密方式: $method")
//                }
//                return@forEach
//            }
//
//            if (!line.startsWith("#")) {
//                result += SegmentItem(
//                    sequence = seq,
//                    url = resolveUrl(baseUrl, line),
//                    key = currentKey
//                )
//                seq++
//            }
//        }
//
//        if (result.isEmpty()) error("m3u8 未解析到分片")
//        return result
//    }
//
//    private fun decryptAes128(segmentEncrypted: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
//        if (key.size != 16) error("AES-128 key length must be 16, actual=${key.size}")
//        val secretKey = SecretKeySpec(key, "AES")
//        val ivSpec = IvParameterSpec(iv)
//
//        return try {
//            Cipher.getInstance("AES/CBC/PKCS5Padding").run {
//                init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
//                doFinal(segmentEncrypted)
//            }
//        } catch (_: Throwable) {
//            // 有些源可能是无填充
//            Cipher.getInstance("AES/CBC/NoPadding").run {
//                init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
//                doFinal(segmentEncrypted)
//            }
//        }
//    }
//
//    private suspend fun downloadM3u8VideoWithDecrypt(
//        taskId: Long,
//        entryUrl: String,
//        fileName: String,
//        referer: String?,
//        userAgent: String?,
//        cookie: String?,
//        saveVideo: SaveToFile,
//        mediaTarget: MediaStoreTarget,
//    ) = coroutineScope {
//        // 1) 拉入口 playlist
//        val entryText = executeRequest(entryUrl, referer, userAgent, cookie).use {
//            it.body?.string() ?: error("Empty m3u8 body")
//        }
//
//        // 2) master -> 选最高码率
//        val mediaUrl: String
//        val mediaText: String
//        if (entryText.contains("#EXT-X-STREAM-INF", true)) {
//            val variants = parseMasterPlaylist(entryUrl, entryText)
//            val selected = variants.maxByOrNull { it.bandwidth } ?: error("master playlist 无可用码流")
//            mediaUrl = selected.url
//            mediaText = executeRequest(mediaUrl, referer, userAgent, cookie).use {
//                it.body?.string() ?: error("Empty media playlist")
//            }
//        } else {
//            mediaUrl = entryUrl
//            mediaText = entryText
//        }
//
//        val segments = parseMediaPlaylistWithKeys(mediaUrl, mediaText)
//
//        // KEY 缓存
//        val keyCache = ConcurrentHashMap<String, ByteArray>()
//        suspend fun getKeyBytes(keyUri: String): ByteArray {
//            keyCache[keyUri]?.let { return it }
//            val keyBytes = executeRequest(keyUri, referer, userAgent, cookie).use { resp ->
//                resp.body?.bytes() ?: error("Empty key body: $keyUri")
//            }
//            val normalized = when {
//                keyBytes.size == 16 -> keyBytes
//                keyBytes.size > 16 -> keyBytes.copyOfRange(0, 16)
//                else -> error("Invalid AES-128 key length=${keyBytes.size}, uri=$keyUri")
//            }
//            keyCache[keyUri] = normalized
//            return normalized
//        }
//
//        val tempDir = File(applicationContext.cacheDir, "m3u8_${taskId}_${System.currentTimeMillis()}").apply { mkdirs() }
//        val semaphore = Semaphore(4)
//        val done = AtomicInteger(0)
//
//        try {
//            // 3) 并发下载+解密到临时文件
//            segments.mapIndexed { index, seg ->
//                async {
//                    semaphore.withPermit {
//                        if (isStopped) error("Worker stopped")
//
//                        val rawBytes = executeRequest(seg.url, referer, userAgent, cookie).use { resp ->
//                            resp.body?.bytes() ?: error("Empty segment body: ${seg.url}")
//                        }
//
//                        val finalBytes = if (seg.key?.method == "AES-128") {
//                            val keyUri = seg.key.keyUri ?: error("AES-128 key uri is null")
//                            val key = getKeyBytes(keyUri)
//                            val iv = seg.key.iv ?: defaultIvForSequence(seg.sequence)
//                            decryptAes128(rawBytes, key, iv)
//                        } else {
//                            rawBytes
//                        }
//
//                        val segFile = File(tempDir, "seg_${index.toString().padStart(6, '0')}.ts")
//                        segFile.outputStream().use { it.write(finalBytes) }
//
//                        val finished = done.incrementAndGet()
//                        val progress = ((finished * 90L) / segments.size).toInt().coerceIn(0, 90)
//                        downloadRepo.updateProgress(taskId, progress)
//                        setProgress(workDataOf("progress" to progress))
//                        setForeground(
//                            buildForegroundInfo(
//                                applicationContext.getString(R.string.base_general_download_now),
//                                fileName.showName,
//                                progress
//                            )
//                        )
//                    }
//                }
//            }.awaitAll()
//
//            // 4) 顺序合并
//            val (_, filePath, outputStream) = mediaTarget
//            outputStream.use { out ->
//                segments.indices.forEach { i ->
//                    val segFile = File(tempDir, "seg_${i.toString().padStart(6, '0')}.ts")
//                    segFile.inputStream().use { it.copyTo(out) }
//
//                    val progress = (90 + ((i + 1) * 10 / segments.size)).coerceIn(90, 100)
//                    downloadRepo.updateProgress(taskId, progress)
//                    setProgress(workDataOf("progress" to progress))
//                    setForeground(
//                        buildForegroundInfo(
//                            applicationContext.getString(R.string.base_general_download_now),
//                            fileName.showName,
//                            progress
//                        )
//                    )
//                }
//            }
//
//            saveVideo.success(applicationContext, mediaTarget)
//            downloadRepo.markSuccess(taskId, filePath)
//            notificationHelper.notifyDownloadResult(
//                taskId,
//                title = applicationContext.getString(R.string.base_general_download_completed),
//                fileName = fileName.showName,
//                content = filePath,
//            )
//        } catch (t: Throwable) {
//            saveVideo.failure(applicationContext, mediaTarget)
//            throw t
//        } finally {
//            tempDir.listFiles()?.forEach { it.delete() }
//            tempDir.delete()
//        }
//    }

}

object DownloadVideoWorkStarter {

    // todo 不知道能不能设置为如果是同一个taskId，则keep，如果是不同的taskId，则排队
    fun enqueue(context: Context, taskId: Long) {
        val data = workDataOf(DownloadVideoWorker.KEY_TASK_ID to taskId)

        val request = OneTimeWorkRequestBuilder<DownloadVideoWorker>()
            .setInputData(data)
            .addTag(TASK_TAG)
            .addTag("${TASK_TAG}:$taskId")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "${TASK_TAG}:$taskId",
            ExistingWorkPolicy.KEEP, // 如果存在具有相同唯一名称的挂起（未完成）工作，则不执行任何操作。否则，插入新指定的作品
            request
        )
    }
}