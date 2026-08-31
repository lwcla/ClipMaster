package com.cla.clip.feature.torrent.search.network

import com.cla.clip.feature.torrent.search.model.contract.ProviderFailureReason
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Provider 唯一可依赖的网络接口；调用方不能通过此接口取得或记录原始请求/响应对象。 */
interface TorrentSearchNetworkClient {
    /** HTTPS GET 文本；透明解压后超过 5 MiB 会抛 ResponseTooLargeException。 */
    suspend fun getText(url: String): String

    /** HTTPS GET JSON；当前与文本读取共用严格大小限制。 */
    suspend fun getJson(url: String): String

    /** HTTPS POST JSON；请求内容不得被网络层日志记录。 */
    suspend fun postJson(url: String, json: String): String

    /** HTTPS 表单提交；字段内容不得被网络层日志记录。 */
    suspend fun submitForm(url: String, fields: Map<String, String>): String

    /** 获取必要的重定向地址；不存在时返回 null，且地址不得写日志。 */
    suspend fun redirectLocation(url: String): String?
}

/** 网络边界稳定异常基类，聚合器据此映射可测试的 ProviderFailureReason。 */
sealed class TorrentSearchNetworkException : IOException()

/** HTTPS-only 策略或非成功 HTTP 状态的稳定异常。 */
class HttpDeniedException(
    /** 可安全记录的 HTTP 状态码；HTTPS 策略拒绝时为空。 */
    val statusCode: Int? = null,
) : TorrentSearchNetworkException()

/** 透明解压后的文本大小超过固定 5 MiB 上限。 */
class ResponseTooLargeException : TorrentSearchNetworkException()

/** Cloudflare 挑战响应；当前阶段不接入 WebView 求解。 */
class CloudflareLockedException(
    /** 可安全记录的 HTTP 状态码；没有响应时为空。 */
    val statusCode: Int? = null,
) : TorrentSearchNetworkException()

/** 直接 OkHttp 网络实现，所有构造参数只配置客户端，不会在构造期间发起请求。 */
class OkHttpTorrentSearchNetworkClient internal constructor(
    /** 共享 OkHttp 实例，由 Hilt 单例提供以统一 Dispatcher 限流。 */
    private val client: OkHttpClient,
    /** 仅模块内 JVM 测试允许回环 HTTP fixture；公开构造与 Hilt 永远固定为 false。 */
    private val allowHttpForLocalTests: Boolean = false,
) : TorrentSearchNetworkClient {
    /** 读取 HTTPS 文本响应；响应体在解析器之前按解压后字节数限制。 */
    override suspend fun getText(url: String): String = executeText(newRequest(url))

    /** JSON 与文本使用同一安全读取边界，避免内容类型伪装绕过大小限制。 */
    override suspend fun getJson(url: String): String = executeText(newRequest(url))

    /** 提交 JSON；内容类型固定为 UTF-8 JSON，正文永不写日志。 */
    override suspend fun postJson(url: String, json: String): String {
        /** JSON 媒体类型是稳定协议常量，不含用户或 Provider 数据。 */
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        /** 仅交给 OkHttp 的请求体，日志层无法获取该实例。 */
        val requestBody = json.toRequestBody(jsonMediaType)
        return executeText(newRequest(url).newBuilder().post(requestBody).build())
    }

    /** 提交 application/x-www-form-urlencoded 数据；字段由 OkHttp 编码且不会被记录。 */
    override suspend fun submitForm(url: String, fields: Map<String, String>): String {
        /** OkHttp 表单构造器负责标准编码，避免在本模块自行拼接或泄漏字段内容。 */
        val formBuilder = FormBody.Builder()
        fields.forEach { (name, value) -> formBuilder.add(name, value) }
        /** 不可变表单请求体只交给 OkHttp，日志层无法取得字段原文。 */
        val requestBody = formBuilder.build()
        return executeText(newRequest(url).newBuilder().post(requestBody).build())
    }

    /** 读取必要的 Location 响应头；不跟随重定向以保留调用方所需地址。 */
    override suspend fun redirectLocation(url: String): String? {
        /** 禁止自动跟随的派生客户端，不创建网络调用直到 execute 时。 */
        val redirectClient = client.newBuilder().followRedirects(false).build()
        /** 由 suspendCancellableCoroutine 包装的 Call，取消时会取消底层请求。 */
        val response = execute(redirectClient.newCall(newRequest(url)))
        response.use { managedResponse ->
            return managedResponse.header("Location")
        }
    }

    /** 创建仅允许 HTTPS 的 GET 请求，HTTP 在进入 OkHttp 前稳定拒绝。 */
    private fun newRequest(url: String): Request {
        /** 解析后的地址用于检查协议；无效地址按 HTTP 拒绝而非泄漏原始 URL。 */
        val parsedUrl = runCatching { url.toHttpUrl() }.getOrElse { throw HttpDeniedException() }
        if (!parsedUrl.isHttps && !allowHttpForLocalTests) throw HttpDeniedException()
        return Request.Builder().url(parsedUrl).build()
    }

    /** 执行请求并在成功状态下读取有上限的解压后文本。 */
    private suspend fun executeText(request: Request): String {
        /** 独立 Call 仅在该请求作用域内存在，协程取消会取消该 Call。 */
        val response = execute(client.newCall(request))
        response.use { managedResponse ->
            if (!managedResponse.isSuccessful) {
                if (managedResponse.isCloudflareChallenge()) throw CloudflareLockedException(managedResponse.code)
                throw HttpDeniedException(managedResponse.code)
            }
            /** 非空响应体才可读取；空体按空文本交给 Provider 的解析策略。 */
            val responseBody = managedResponse.body ?: return ""
            return responseBody.readLimitedText()
        }
    }

    /** 把 OkHttp 异步 Call 桥接为可取消挂起点，保证外层取消立即传给底层网络请求。 */
    private suspend fun execute(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            /** 网络层失败直接交回协程；聚合器负责稳定分类且不记录异常 message。 */
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            /** 网络层成功时返回 Response 所有权，由上层 use 负责关闭。 */
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    /** 网络配置与响应大小的统一常量入口，供 Hilt 和测试复用同一安全边界。 */
    companion object {
        /** 透明解压后的文本最大字节数，固定 5 MiB，达到上限即拒绝进入 parser。 */
        const val MAX_TEXT_RESPONSE_BYTES: Long = 5L * 1024L * 1024L

        /** 建立全局共享 OkHttp 客户端；仅配置 Dispatcher 与超时，不执行网络。 */
        fun createOkHttpClient(): OkHttpClient {
            /** Dispatcher 限制整个功能模块最大并发，Provider 不得自行覆盖。 */
            val dispatcher = Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 5
            }
            return OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectTimeout(10L, TimeUnit.SECONDS)
                .readTimeout(30L, TimeUnit.SECONDS)
                .build()
        }
    }
}

/** 只依据低敏 HTTP 状态和固定响应头识别 Cloudflare 挑战，不读取或记录响应正文。 */
private fun Response.isCloudflareChallenge(): Boolean {
    /** 固定服务端标识；仅用于内存分类，绝不进入日志事件。 */
    val hasCloudflareServerHeader = header("Server")?.equals("cloudflare", ignoreCase = true) == true
    /** Cloudflare 的请求标识头可覆盖限流等非旧状态码挑战响应。 */
    val hasCloudflareRayHeader = header("CF-RAY") != null
    return hasCloudflareServerHeader || hasCloudflareRayHeader
}

/** 在透明解压后的 ResponseBody source 上逐字节计数，防止大 body 进入 Provider parser。 */
private fun ResponseBody.readLimitedText(): String {
    /** 解压后的 source，由 OkHttp 自动处理 Content-Encoding。 */
    val source = source()
    /** 累积读取缓冲区，最多保存安全上限的文本字节。 */
    val output = Buffer()
    /** 每次读取的固定小块，避免依据 Content-Length 错误信任压缩体大小。 */
    val chunkSize = 8_192L
    /** 已读取的解压后字节总数，严格用于上限判断。 */
    var totalBytes = 0L
    while (true) {
        /** 本次读取字节数；-1 表示响应体结束。 */
        val readBytes = source.read(output, chunkSize)
        if (readBytes == -1L) break
        totalBytes += readBytes
        if (totalBytes > OkHttpTorrentSearchNetworkClient.MAX_TEXT_RESPONSE_BYTES) throw ResponseTooLargeException()
    }
    return output.readUtf8()
}

/** 将网络、HTTP 与 Cloudflare 异常映射为稳定 reason；取消由协调器单独处理。 */
fun Throwable.toProviderFailureReason(): ProviderFailureReason = when (this) {
    is ResponseTooLargeException -> ProviderFailureReason.RESPONSE_TOO_LARGE
    is CloudflareLockedException -> ProviderFailureReason.CLOUDFLARE_LOCKED
    is HttpDeniedException -> ProviderFailureReason.HTTP_DENIED
    is IOException -> ProviderFailureReason.NETWORK
    else -> ProviderFailureReason.UNKNOWN
}

/** 提取允许进入低敏日志的 HTTP 状态码；其他异常不泄漏 message 或请求上下文。 */
fun Throwable.toProviderHttpStatusCode(): Int? = when (this) {
    is HttpDeniedException -> statusCode
    is CloudflareLockedException -> statusCode
    else -> null
}
