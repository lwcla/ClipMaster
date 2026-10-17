package com.cla.clip.base.general.backup

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.io.DataInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import com.cla.clip.base.general.backup.webdav.WebDavUploadRetryClassifier

/** 合成 HTTP 响应保护传输和列表契约，不接触真实网络与凭据。 */
class WebDavDirectUploadTest {
    /** 服务端已收完包但响应丢失时，业务补传仍使用同一路径和字节，不依赖底层自动重发。 */
    @Test fun lostResponseCanBeRetriedWithIdenticalPackageAndPath() = runBlocking {
        /** 本机合成服务，绝不访问真实 WebDAV。 */
        val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)); soTimeout = 5000 }
        /** 首次响应保持阻塞，直到客户端确认超时后显式放行。 */
        val releaseFirst = CountDownLatch(1)
        /** 服务端实际接收的路径及完整 payload，均为合成数据。 */
        val received = CopyOnWriteArrayList<Pair<String, ByteArray>>()
        /** 有界独立服务线程。 */
        val executor = Executors.newSingleThreadExecutor()
        /** 两次接收分别代表第一次请求和调用方明确发起的补传。 */
        val serving = executor.submit<Unit> {
            // attempt 是合成服务接收次数，不是生产重试计数。
            repeat(2) { attempt ->
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    /** 头读取不得吞掉二进制包内容。 */
                    val input = DataInputStream(socket.getInputStream())
                    /** 请求行只保存合成本机路径。 */
                    val path = input.readAsciiHeaderLine().orEmpty()
                    /** 当前请求的明确 Content-Length。 */
                    var length = 0
                    while (true) {
                        /** 空行结束头部。 */
                        val line = input.readAsciiHeaderLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    /** 真实读取全部合成正文，证明响应丢失可能发生在完整上传之后。 */
                    val payload = ByteArray(length)
                    input.readFully(payload)
                    received += path to payload
                    if (attempt == 0) check(releaseFirst.await(5L, TimeUnit.SECONDS))
                    else socket.getOutputStream().write("HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                }
            }
        }
        try {
            /** 真实读超时仅用于有界回环测试，不改变产品超时配置。 */
            val client = WebDavClient(OkHttpClient.Builder().readTimeout(1L, TimeUnit.SECONDS).callTimeout(4L, TimeUnit.SECONDS).build())
            /** 无凭据且明确允许本机 HTTP。 */
            val target = WebDavConfig("http://127.0.0.1:${server.localPort}", "", "", "/", true)
            /** 两次请求必须复用同一对象和固定包。 */
            val packageExport = export()
            try { client.uploadBackup(target, packageExport, "A"); fail("首次应等待响应超时") }
            catch (failure: BackupFailure.RemoteFailed) { assertTrue(WebDavUploadRetryClassifier.isRecoverable(failure)) }
            assertEquals(1, received.size)
            releaseFirst.countDown()
            client.uploadBackup(target, packageExport, "A")
            serving.get(5L, TimeUnit.SECONDS)
            assertEquals(2, received.size)
            assertEquals(received[0].first, received[1].first)
            assertArrayEquals(packageExport.packageFile.readBytes(), received[0].second)
            assertArrayEquals(received[0].second, received[1].second)
        } finally {
            releaseFirst.countDown()
            server.close()
            executor.shutdownNow()
        }
    }

    /** 本机 HTTP 服务穿过 OkHttp 的真实跟进层，503 提示也不能让同一 ZIP 重发。 */
    @Test fun serviceUnavailableRetryAfterZeroDoesNotRepeatZipPut() = runBlocking {
        assertSinglePutWithRetryAfter("synthetic.zip")
    }

    /** 连接探测的小文本 PUT 仍保持单次请求，不再由正式备份上传外部摘要。 */
    @Test fun serviceUnavailableRetryAfterZeroDoesNotRepeatManifestPut() = runBlocking {
        assertSinglePutWithRetryAfter("synthetic.zip.manifest.json")
    }

    /** 有界合成服务验证实际网络跟进层，分别保护 ZIP 和 manifest 的一次性正文。 */
    private suspend fun assertSinglePutWithRetryAfter(failingName: String) {
        /** 本机临时端口，不连接任何外部服务。 */
        val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        /** 请求线程的确定计数，断言在同步请求完成之后读取。 */
        val zipPuts = AtomicInteger()
        /** 独立服务线程只处理合成请求，关闭监听后必须有界退出。 */
        val executor = Executors.newSingleThreadExecutor()
        /** 完整消费请求体，按 Connection: close 逐次响应，便于发现客户端自动重发。 */
        val serving = executor.submit<Unit> {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        /** ASCII 头读取不会提前吞掉后续二进制 payload。 */
                        val input = DataInputStream(socket.getInputStream())
                        /** 首行只用于区分合成 PUT 和目录请求。 */
                        val requestLine = input.readAsciiHeaderLine().orEmpty()
                        /** 按 Content-Length 消费本测试的有界合成请求体。 */
                        var length = 0
                        while (true) {
                            /** 空行代表请求头结束。 */
                            val line = input.readAsciiHeaderLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        input.readFully(ByteArray(length))
                        /** 503 跟进是本回归的唯一失败触发，目录查询保持可用。 */
                        val response = if (requestLine.startsWith("PUT ") &&
                            requestLine.substringBefore(" HTTP/").endsWith("/$failingName")) {
                            zipPuts.incrementAndGet()
                            "HTTP/1.1 503 Unavailable\r\nRetry-After: 0\r\n"
                        } else "HTTP/1.1 200 OK\r\n"
                        socket.getOutputStream().write((response + "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    }
                } catch (failure: SocketException) {
                    if (!server.isClosed) throw failure
                }
            }
        }
        try {
            /** 有限总超时避免本机测试服务异常导致挂起。 */
            val client = OkHttpClient.Builder().callTimeout(5L, TimeUnit.SECONDS).build()
            /** 明确仅允许此合成本机 HTTP 配置。 */
            val target = WebDavConfig("http://127.0.0.1:${server.localPort}", "", "", "/", true)
            assertThrows(BackupFailure.RemoteFailed::class.java) {
                runBlocking {
                    if (failingName.endsWith(".json")) WebDavClient(client).uploadText(target, failingName, "{}")
                    else WebDavClient(client).uploadBackup(target, export())
                }
            }
            assertEquals(1, zipPuts.get())
        } finally {
            server.close()
            try { serving.get(5L, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
        }
    }

    /** 测试专用有界 ASCII 头读取，不提前吞掉二进制正文，也不使用废弃的 readLine。 */
    private fun DataInputStream.readAsciiHeaderLine(): String? {
        /** 每行仅保存 HTTP 头，最多 8 KiB 防止合成服务意外挂起。 */
        val line = StringBuilder()
        while (true) {
            /** 字节读取保留后续 payload 所有权。 */
            val byte = read()
            if (byte == -1) return line.toString().takeIf { it.isNotEmpty() }
            if (byte == 10) return line.toString().trimEnd('\r')
            check(line.length < 8192) { "synthetic_header_too_long" }
            line.append(byte.toChar())
        }
    }
    /** 独占合成文件目录。 */
    @get:Rule val folder = TemporaryFolder(File("build"))

    /** 正常发布只有 ZIP PUT，目录和独立摘要不再成为前置失败点。 */
    @Test fun publishesOnlyZipWithoutDirectoryPreflight() = runBlocking {
        /** 固定方法/路径用于验证发布顺序。 */
        val requests = mutableListOf<String>()
        WebDavClient(client(requests)).uploadBackup(config(), export())
        assertEquals(listOf("PUT synthetic.zip"), requests)
        assertFalse(requests.any { it.startsWith("GET") || it.contains(".tmp") || it.contains("latest.json") })
    }

    /** ZIP 失败不得继续发布 manifest，也不能吞成成功或重发 ZIP。 */
    @Test fun zipFailureStopsBeforeManifest() {
        /** 本次失败的实际请求序列。 */
        val requests = mutableListOf<String>()
        assertThrows(BackupFailure.RemoteFailed::class.java) {
            runBlocking { WebDavClient(client(requests, "synthetic.zip")).uploadBackup(config(), export()) }
        }
        assertEquals(1, requests.count { it.startsWith("PUT") })
    }

    /** 历史摘要不可写不会影响新上传，客户端完全不请求它。 */
    @Test fun externalManifestFailureCannotAffectZipPublication() = runBlocking {
        /** 两阶段的确定失败响应。 */
        val requests = mutableListOf<String>()
        WebDavClient(client(requests, "synthetic.zip.manifest.json")).uploadBackup(config(), export())
        assertEquals(listOf("PUT synthetic.zip"), requests)
    }

    /** 离线 IO 错误明确失败，本轮不尝试第二次 ZIP 请求。 */
    @Test fun networkFailureIsNotRetried() {
        /** 固定计数，不依赖真实网络或线程调度。 */
        var attempts = 0
        /** 拦截器代表不可用的连接。 */
        val client = OkHttpClient.Builder().addInterceptor {
            attempts++
            throw IOException("synthetic")
        }.build()
        assertThrows(BackupFailure.RemoteFailed::class.java) {
            runBlocking { WebDavClient(client).uploadBackup(config(), export()) }
        }
        assertEquals(1, attempts)
    }

    /** 列表只扫描目录和包摘要，存在或损坏的历史指针都不参与读取。 */
    @Test fun listingDoesNotReadSharedPointer() = runBlocking {
        /** 空目录合成响应，没有真实用户文件。 */
        val requests = mutableListOf<String>()
        assertTrue(WebDavClient(client(requests)).listBackups(config()).isEmpty())
        assertTrue(requests.all { it.startsWith("PROPFIND") })
    }

    /** 合成响应支持精确指定一个阶段失败，所有请求都在应用拦截器内终止。 */
    private fun client(requests: MutableList<String>, failingName: String = ""): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            /** 仅记录合成路径和方法，不读取认证信息。 */
            val request = chain.request()
            requests += "${request.method} ${request.url.pathSegments.last()}"
            /** PROPFIND 返回最小合法目录响应，PUT 返回空内容。 */
            val body = if (request.method == "PROPFIND") "<d:multistatus xmlns:d=\"DAV:\"/>" else ""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (failingName.isNotBlank() && request.url.pathSegments.last() == failingName) 500 else 200).message("synthetic")
                .body(body.toResponseBody()).build()
        }.build()

    /** 无凭据的保留示例域，不会进行真实连接。 */
    private fun config(): WebDavConfig = WebDavConfig("https://example.invalid", "", "", "/")

    /** 任意合成字节只验证传输；ZIP 格式和恢复校验由现有协议回归保护。 */
    private fun export(): BackupExportResult {
        /** 每个测试只有一个独立 payload。 */
        val payload = folder.newFile("synthetic.zip").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        /** 最小合成摘要，不含业务内容。 */
        val manifest = BackupManifest(createdAt = 100L, applicationId = "synthetic", schemaVersion = 4,
            appVersionCode = 1, appVersionName = "test", deviceLabel = "synthetic", source = BackupSource.WebDavAuto,
            snapshotFileName = payload.name, checksum = "synthetic", summary = BackupSummary())
        return BackupExportResult(payload, manifest, BackupJson.encodeManifest(manifest), payload.name,
            "${payload.name}.manifest.json", folder.root)
    }
}
