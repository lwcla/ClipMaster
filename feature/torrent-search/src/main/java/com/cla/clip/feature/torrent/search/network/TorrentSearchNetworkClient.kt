package com.cla.clip.feature.torrent.search.network

import com.cla.clip.feature.torrent.search.model.contract.ProviderFailureReason
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
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
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Provider 唯一可依赖的网络接口，禁止向调用方暴露原始请求或响应对象。 */
interface TorrentSearchNetworkClient {
    /** HTTPS GET 文本；解压后超过 5 MiB 抛出 ResponseTooLargeException。 */
    suspend fun getText(url: String): String

    /** HTTPS GET JSON；与文本共用严格大小和取消边界。 */
    suspend fun getJson(url: String): String

    /** HTTPS POST JSON；正文不得进入日志。 */
    suspend fun postJson(url: String, json: String): String

    /** HTTPS 表单提交；字段不得进入日志。 */
    suspend fun submitForm(url: String, fields: Map<String, String>): String

    /** 获取合法重定向的 Location；不存在时为空。 */
    suspend fun redirectLocation(url: String): String?
}

/** 网络边界稳定异常基类，聚合器据此映射失败原因。 */
sealed class TorrentSearchNetworkException : IOException()

/** HTTPS 策略或 HTTP 状态拒绝。 */
class HttpDeniedException(
    /** 可进入低敏日志的状态码；scheme 或地址解析拒绝时为空。 */
    val statusCode: Int? = null,
) : TorrentSearchNetworkException()

/** 解压后的响应文本超过固定 5 MiB 上限。 */
class ResponseTooLargeException : TorrentSearchNetworkException()

/** Cloudflare 挑战响应，当前内核只分类而不启动 WebView。 */
class CloudflareLockedException(
    /** 可进入低敏日志的挑战状态码。 */
    val statusCode: Int? = null,
) : TorrentSearchNetworkException()

/** 直接 OkHttp 网络实现；构造只接收共享客户端且不会发起请求。 */
class OkHttpTorrentSearchNetworkClient internal constructor(
    /** 功能内共享 OkHttp 客户端，由 Hilt 限定符隔离宿主全局绑定。 */
    private val client: OkHttpClient,
) : TorrentSearchNetworkClient {
    /** 在 OkHttp callback 线程完成状态校验与限量 body 读取。 */
    override suspend fun getText(url: String): String = executeText(newRequest(url))

    /** JSON 与文本共用相同网络安全边界。 */
    override suspend fun getJson(url: String): String = executeText(newRequest(url))

    /** 构造 JSON 请求并在同一 Call 生命周期读取有上限响应。 */
    override suspend fun postJson(url: String, json: String): String {
        /** 固定 JSON 媒体类型不含用户数据。 */
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        /** 请求体只交给 OkHttp，日志层无法访问。 */
        val requestBody = json.toRequestBody(jsonMediaType)
        return executeText(newRequest(url).newBuilder().post(requestBody).build())
    }

    /** 由 OkHttp 编码表单字段并读取有上限响应。 */
    override suspend fun submitForm(url: String, fields: Map<String, String>): String {
        /** 表单构造器避免本模块自行拼接敏感字段。 */
        val formBuilder = FormBody.Builder()
        /** 解构字段只用于即时加入请求体，不进入日志或异常。 */
        fields.forEach { (name, value) -> formBuilder.add(name, value) }
        /** 不可变请求体只交给 OkHttp。 */
        val requestBody = formBuilder.build()
        return executeText(newRequest(url).newBuilder().post(requestBody).build())
    }

    /** 不跟随重定向并复用统一状态校验，挑战和普通拒绝不能绕过分类。 */
    override suspend fun redirectLocation(url: String): String? {
        /** 派生客户端只关闭自动重定向，保留共享 Dispatcher、TLS 和超时。 */
        val redirectClient = client.newBuilder().followRedirects(false).build()
        /** 独立 Call 的取消、响应关闭和恢复竞争由 executeInCallback 统一处理。 */
        val call = redirectClient.newCall(newRequest(url))
        /** response 只在 callback 线程读取允许公开的 Location。 */
        return executeInCallback(call) { response ->
            response.requireAcceptedStatus(allowRedirect = true)
            response.header("Location")
        }
    }

    /** 创建严格 HTTPS 请求；无效或明文地址在创建 Call 前拒绝。 */
    private fun newRequest(url: String): Request {
        /** 解析结果仅用于 scheme 校验，不进入日志。 */
        val parsedUrl = runCatching { url.toHttpUrl() }.getOrElse { throw HttpDeniedException() }
        if (!parsedUrl.isHttps) throw HttpDeniedException()
        return Request.Builder().url(parsedUrl).build()
    }

    /** 在 OkHttp callback 线程校验状态并完整读取有上限文本，调用方线程保持挂起不阻塞。 */
    private suspend fun executeText(request: Request): String {
        /** 当前请求唯一 Call，协程取消会在 body 读取期间继续调用 cancel。 */
        val call = client.newCall(request)
        /** response 只在 callback 线程完成状态校验和限量读取。 */
        return executeInCallback(call) { response ->
            response.requireAcceptedStatus(allowRedirect = false)
            /** 空 body 按空字符串交给 Provider 决定解析语义。 */
            val body = response.body ?: return@executeInCallback ""
            body.readLimitedText()
        }
    }

    /**
     * 将 Call 全生命周期桥接到可取消协程：transform 始终在 OkHttp callback/IO 线程执行，
     * response 在恢复调用方前关闭；prompt cancellation 保证取消与恢复竞争不会交付过期结果。
     */
    private suspend fun <T> executeInCallback(
        /** 当前请求的唯一 Call；协程取消会直接取消它及正在读取的响应体。 */
        call: Call,
        /** 只在 callback 线程、response.use 作用域内执行的响应转换。 */
        transform: (Response) -> T,
    /** continuation 连接协程取消与唯一 OkHttp Call，不向调用方暴露 response。 */
    ): T = suspendCancellableCoroutine { continuation ->
        /** 任意时点取消都会打断连接或正在阻塞读取的 body。 */
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            /** 连接、TLS、读取或取消失败只恢复异常，不读取异常 message。 */
            override fun onFailure(call: Call, e: IOException) {
                /** CancellableContinuation 原子协调取消与异常恢复；取消先发生时不会交付该异常。 */
                continuation.resumeWithException(e)
            }

            /** 在 callback 线程完成状态校验和 body 消费，并保证所有路径关闭 response。 */
            override fun onResponse(call: Call, response: Response) {
                try {
                    /** 转换结果在 response 关闭前完全生成，调用方不会持有底层流。 */
                    val result = response.use(transform)
                    /** CancellableContinuation 的 prompt cancellation 保证取消先发生时不会交付结果。 */
                    continuation.resume(result)
                // failure 表示状态校验或 body 读取失败，只按类型恢复且不读取 message。
                } catch (failure: Exception) {
                    /** body 读取或状态校验失败只按类型恢复，绝不拼接 message。 */
                    continuation.resumeWithException(failure)
                }
            }
        })
    }

    /** 网络配置和响应上限的统一常量入口。 */
    companion object {
        /** 透明解压后的文本最大字节数，固定 5 MiB。 */
        const val MAX_TEXT_RESPONSE_BYTES: Long = 5L * 1024L * 1024L

        /** 建立共享客户端，只配置 Dispatcher 和超时且不执行网络。 */
        fun createOkHttpClient(): OkHttpClient {
            /** Dispatcher 固定整个功能最大并发 64、同 host 5。 */
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

/** 校验成功或调用方允许的重定向状态，并统一分类 Cloudflare 与普通拒绝。 */
private fun Response.requireAcceptedStatus(allowRedirect: Boolean) {
    /** 3xx 只供 redirectLocation 读取 Location，普通文本请求仍拒绝。 */
    val isAcceptedRedirect = allowRedirect && code in 300..399
    if (isSuccessful || isAcceptedRedirect) return
    if (isCloudflareChallenge()) throw CloudflareLockedException(code)
    throw HttpDeniedException(code)
}

/** 只在 403/429/503 挑战状态且存在 Cloudflare 特征头时分类锁定。 */
private fun Response.isCloudflareChallenge(): Boolean {
    /** 允许识别的挑战状态，避免 CF 404 等普通错误被误报。 */
    val isChallengeStatus = code == 403 || code == 429 || code == 503
    /** Server 头的 Cloudflare 标识。 */
    val hasCloudflareServerHeader = header("Server")?.equals("cloudflare", ignoreCase = true) == true
    /** CF-RAY 请求标识头。 */
    val hasCloudflareRayHeader = header("CF-RAY") != null
    return isChallengeStatus && (hasCloudflareServerHeader || hasCloudflareRayHeader)
}

/** 在 OkHttp 已透明解压的 source 上计数并读取文本。 */
private fun ResponseBody.readLimitedText(): String {
    /** 解压后的响应 source。 */
    val source = source()
    /** 最多保留上限大小的内存缓冲。 */
    val output = Buffer()
    /** 固定 8 KiB 读取块，不能信任压缩 Content-Length。 */
    val chunkSize = 8_192L
    /** 当前解压后累计字节数。 */
    var totalBytes = 0L
    while (true) {
        /** 当前读取字节数，-1 表示 body 结束。 */
        val readBytes = source.read(output, chunkSize)
        if (readBytes == -1L) break
        totalBytes += readBytes
        if (totalBytes > OkHttpTorrentSearchNetworkClient.MAX_TEXT_RESPONSE_BYTES) throw ResponseTooLargeException()
    }
    return output.readUtf8()
}

/** 将网络异常映射稳定失败原因；协程取消由协调器单独处理。 */
fun Throwable.toProviderFailureReason(): ProviderFailureReason = when (this) {
    is ResponseTooLargeException -> ProviderFailureReason.RESPONSE_TOO_LARGE
    is CloudflareLockedException -> ProviderFailureReason.CLOUDFLARE_LOCKED
    is HttpDeniedException -> ProviderFailureReason.HTTP_DENIED
    is SocketTimeoutException -> ProviderFailureReason.TIMEOUT
    is IOException -> ProviderFailureReason.NETWORK
    else -> ProviderFailureReason.UNKNOWN
}

/** 提取允许进入日志的 HTTP 状态码，其他异常不泄漏上下文。 */
fun Throwable.toProviderHttpStatusCode(): Int? = when (this) {
    is HttpDeniedException -> statusCode
    is CloudflareLockedException -> statusCode
    else -> null
}
