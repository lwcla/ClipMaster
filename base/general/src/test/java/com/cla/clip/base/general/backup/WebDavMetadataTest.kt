package com.cla.clip.base.general.backup

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** 远端列表和保留清理只读目录元数据，不依赖外部摘要，也不删除历史 safety 或不可识别文件。 */
class WebDavMetadataTest {
    /** 即使集合名称像标准旧 ZIP，也不能列为备份或对它执行递归 DELETE。 */
    @Test fun zipNamedCollectionsNeverEnterRetentionCandidates() = runBlocking {
        /** 真实请求序列应只有目录读取，没有任何删除。 */
        val requests = CopyOnWriteArrayList<String>()
        /** 两个合成集合分别覆盖明确 DAV 类型及只带目录尾斜杠的兼容响应。 */
        val xml = """<d:multistatus xmlns:d="DAV:">
            <d:response><d:href>/a/clip_master_backup_device_20261010_000001.zip</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
            <d:response><d:href>/a/clip_master_backup_device_20261009_000001.zip/</d:href></d:response>
            </d:multistatus>"""
        /** 核心解析和清理保持真实，仅替换服务响应。 */
        val client = WebDavClient(OkHttpClient.Builder().addInterceptor { chain ->
            /** 无用户凭据的合成请求。 */
            val request = chain.request()
            requests += request.method
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(207).message("synthetic")
                .body(xml.toResponseBody()).build()
        }.build())
        assertTrue(client.listBackups(WebDavConfig("https://example.invalid", "", "", "/a/")).isEmpty())
        assertEquals(0, client.pruneBackups(WebDavConfig("https://example.invalid", "", "", "/a/"), 0).deletedCount)
        assertEquals(listOf("PROPFIND", "PROPFIND"), requests)
    }

    /** 派生 WebDAV 客户端不继承全局完整 URL 日志，仍复用底层请求栈。 */
    @Test fun webDavDoesNotInheritFullUrlLogging() = runBlocking {
        /** 捕获第三方 logger 输出，不能包含合成服务 URL。 */
        val messages = CopyOnWriteArrayList<String>()
        /** 复现应用共享客户端的第三方完整 URL 日志。 */
        val logger = okhttp3.logging.HttpLoggingInterceptor { messages += it }.apply {
            level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
        }
        /** Logger 后的合成服务避免任何真实网络。 */
        val client = WebDavClient(OkHttpClient.Builder().addInterceptor(logger).addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(201).message("synthetic")
                .body("".toResponseBody()).build()
        }.build())
        client.uploadText(WebDavConfig("https://example.invalid", "", "", "/private/"), "probe.json", "{}")
        assertTrue(messages.isEmpty())
    }
    /** 修改时间只用于展示排序兜底，不能把未知名称误判为可清理普通备份。 */
    @Test fun serverTimeFallbackDoesNotMakeUnknownZipDeletable() {
        /** 未带标准时间戳的合成旧文件。 */
        val file = RemoteBackupFile("clip_master_backup_unknown.zip", "/a/synthetic", null, 123L)
        assertEquals(123L, file.sortCreatedAt)
        assertFalse(file.isRegularBackup)
    }
    /** 非空目录只请求一次；排序和大小来自文件名/目录，元信息不下载 ZIP 或 sidecar。 */
    @Test fun nonEmptyListUsesOnlyDirectoryMetadata() = runBlocking {
        /** 实际方法/合成文件名记录。 */
        val requests = CopyOnWriteArrayList<String>()
        /** 实际远端模型，只允许目录元数据填充。 */
        val files = client(requests).listBackups(WebDavConfig("https://example.invalid", "", "", "/a/"))
        assertEquals(listOf("PROPFIND "), requests)
        assertEquals("clip_master_backup_device_20261011_000001.zip", files.first().fileName)
        assertEquals(3L, files.first().size)
        assertTrue(files.first().sortCreatedAt > 0L)
    }

    /** 保留最新普通 ZIP，旧普通包只 DELETE ZIP；历史摘要和 safety 都不动。 */
    @Test fun retentionDeletesOnlyRecognizedOldZip() = runBlocking {
        /** 副作用序列是本次保留契约。 */
        val requests = CopyOnWriteArrayList<String>()
        /** 清理报告只能统计普通 ZIP，不推断已删除的来源类型。 */
        val result = client(requests).pruneBackups(WebDavConfig("https://example.invalid", "", "", "/a/"), 1)
        assertEquals(listOf("PROPFIND ", "DELETE clip_master_backup_device_20261010_000001.zip"), requests)
        assertEquals(1, result.deletedCount)
        assertTrue(result.deletedKinds.isEmpty())
    }

    /** 合成普通新/旧包、safety、未知包及摘要；只有保留规则能安全识别的包允许删除。 */
    private fun client(requests: MutableList<String>): WebDavClient = WebDavClient(OkHttpClient.Builder().addInterceptor { chain ->
        /** 只读取合成路径，不采集认证或正文。 */
        val request = chain.request()
        requests += "${request.method} ${request.url.pathSegments.last()}"
        /** 小型协议 fixture，不包含真实目录或备份内容。 */
        val xml = listOf("clip_master_backup_device_20261011_000001.zip", "clip_master_backup_device_20261010_000001.zip",
            "clip_master_backup_device_safety_20261009_000001.zip", "clip_master_backup_unknown.zip",
            "clip_master_backup_device_20261010_000001.zip.manifest.json").joinToString(
                prefix = "<d:multistatus xmlns:d=\"DAV:\">", postfix = "</d:multistatus>", separator = "") { name ->
                "<d:response><d:href>/a/$name</d:href><d:propstat><d:prop><d:getcontentlength>3</d:getcontentlength></d:prop></d:propstat></d:response>"
            }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
            .body((if (request.method == "PROPFIND") xml else "").toResponseBody()).build()
    }.build())
}
