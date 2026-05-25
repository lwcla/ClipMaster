package com.cla.clip.feature.magnet.source.cache

import android.util.Xml
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logW
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import org.xmlpull.v1.XmlPullParser

/** Academic Torrents `database.xml` 下载与流式解析。 */
@Singleton
class AcademicTorrentsSource @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "AcademicTorrentsSource"
        private const val DATABASE_URL = "https://academictorrents.com/database.xml"
        private const val XML_TAG_ITEM = "item"
    }

    /** 下载 database.xml 到临时文件；不会把响应体或 URL 查询写入日志。 */
    suspend fun downloadDatabaseXml(
        target: File,
        etag: String?,
        lastModified: String?,
    ): AcademicTorrentsDownloadResult = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val request = Request.Builder()
            .url(DATABASE_URL)
            .apply {
                etag?.takeIf { it.isNotBlank() }?.let { header("If-None-Match", it) }
                lastModified?.takeIf { it.isNotBlank() }?.let { header("If-Modified-Since", it) }
            }
            .build()
        val response = okHttpClient.newCall(request).execute()
        response.use { resp ->
            if (resp.code == 304) {
                logD(TAG) { "Academic Torrents 索引未变化 status=304" }
                return@withContext AcademicTorrentsDownloadResult.NotModified(
                    etag = resp.header("ETag"),
                    lastModified = resp.header("Last-Modified")
                )
            }
            if (!resp.isSuccessful) {
                logW(TAG) { "Academic Torrents 索引下载失败 status=${resp.code}" }
                return@withContext AcademicTorrentsDownloadResult.Failed(MagnetSourceStatusReason.NetworkFailed)
            }
            val body = resp.body ?: return@withContext AcademicTorrentsDownloadResult.Failed(MagnetSourceStatusReason.NetworkFailed)
            target.sink().buffer().use { sink ->
                body.source().use { source ->
                    sink.writeAll(source)
                }
            }
            AcademicTorrentsDownloadResult.Downloaded(
                file = target,
                contentLength = body.contentLength(),
                etag = resp.header("ETag"),
                lastModified = resp.header("Last-Modified")
            )
        }
    }

    /** 流式解析 database.xml，逐条回调合法 item；调用方负责批量入库。 */
    suspend fun parseDatabaseXml(
        file: File,
        onEntry: suspend (AcademicTorrentsEntry) -> Unit,
    ) = withContext(Dispatchers.IO) {
        file.inputStream().use { input ->
            parseDatabaseXml(input, onEntry)
        }
    }

    private suspend fun parseDatabaseXml(
        input: InputStream,
        onEntry: suspend (AcademicTorrentsEntry) -> Unit,
    ) {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, Charsets.UTF_8.name())
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            kotlin.coroutines.coroutineContext.ensureActive()
            if (event == XmlPullParser.START_TAG && parser.name == XML_TAG_ITEM) {
                readItem(parser)?.let { onEntry(it) }
            }
            event = parser.next()
        }
    }

    private fun readItem(parser: XmlPullParser): AcademicTorrentsEntry? {
        var title: String? = null
        var category: String? = null
        var infoHash: String? = null
        var detailUrl: String? = null
        var description: String? = null
        var sizeBytes: Long? = null

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == XML_TAG_ITEM)) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> title = parser.nextTextSafely()
                    "category" -> category = parser.nextTextSafely()
                    "infohash" -> infoHash = parser.nextTextSafely()
                    "guid", "link" -> {
                        val value = parser.nextTextSafely()
                        if (detailUrl.isNullOrBlank()) detailUrl = value
                    }
                    "description" -> description = parser.nextTextSafely()
                    "size" -> sizeBytes = parser.nextTextSafely()?.trim()?.toLongOrNull()
                    else -> parser.skipCurrentTag()
                }
            }
            event = parser.next()
        }
        val safeHash = infoHash?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val safeTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: safeHash
        return AcademicTorrentsEntry(
            infoHash = safeHash,
            title = safeTitle,
            detailUrl = detailUrl?.trim()?.takeIf { it.isNotBlank() },
            sizeBytes = sizeBytes?.takeIf { it >= 0L },
            category = category?.trim()?.takeIf { it.isNotBlank() },
            description = description?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    private fun XmlPullParser.nextTextSafely(): String? {
        return runCatching { nextText() }.getOrNull()
    }

    private fun XmlPullParser.skipCurrentTag() {
        var depth = 1
        while (depth > 0) {
            when (next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }
}

sealed class AcademicTorrentsDownloadResult {
    data class Downloaded(
        val file: File,
        val contentLength: Long,
        val etag: String?,
        val lastModified: String?,
    ) : AcademicTorrentsDownloadResult()

    data class NotModified(
        val etag: String?,
        val lastModified: String?,
    ) : AcademicTorrentsDownloadResult()

    data class Failed(val reason: MagnetSourceStatusReason) : AcademicTorrentsDownloadResult()
}
