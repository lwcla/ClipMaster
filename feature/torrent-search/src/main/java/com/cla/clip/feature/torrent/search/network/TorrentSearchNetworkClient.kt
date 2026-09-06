package com.cla.clip.feature.torrent.search.network

import com.cla.clip.feature.torrent.search.model.contract.ProviderFailureReason
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    /** HTTPS GET 文本；默认不增加头，固定样式头显式传入，解压后超过 5 MiB 拒绝。 */
    suspend fun getText(
        /** 已经通过 Provider URL 策略的请求地址。 */
        url: String,
        /** 固定公开请求头；默认空值保持既有来源行为。 */
        headers: Map<String, String> = emptyMap(),
    ): String

    /** HTTPS GET JSON；固定请求头由调用方显式传入，正文仍共用严格大小和取消边界。 */
    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): String

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

/** 单次响应的受限处理结果，只向显式跳转编排暴露正文或低敏跳转字段。 */
private sealed interface TextResponseOutcome {
    /** 最终成功正文已经过解压后 5 MiB 上限校验。 */
    data class Success(
        /** 完整文本只返回 Provider，不进入异常或日志。 */
        val text: String,
    ) : TextResponseOutcome

    /** 可显式处理的标准跳转响应，response 已关闭且正文未消费。 */
    data class Redirect(
        /** 低敏 HTTP 状态码用于方法转换与拒绝分类。 */
        val statusCode: Int,
        /** 原始 Location 只供 HttpUrl.resolve，禁止写入日志或异常。 */
        val location: String?,
    ) : TextResponseOutcome
}

/** 跳转循环身份由 HTTP method 与 URL 共同组成，允许同 URL 的合法 POST 转 GET。 */
private data class RedirectRequestIdentity(
    /** 实际将发送的方法；方法转换后必须使用新值参与循环判断。 */
    val method: String,
    /** 实际将发送的完整 HttpUrl。 */
    val url: okhttp3.HttpUrl,
)

/** 直接 OkHttp 网络实现；构造只接收共享客户端且不会发起请求。 */
class OkHttpTorrentSearchNetworkClient internal constructor(
    /** 功能内共享 OkHttp 客户端，由 Hilt 限定符隔离宿主全局绑定。 */
    client: OkHttpClient,
) : TorrentSearchNetworkClient {
    /** 由注入客户端派生且强制关闭自动跳转，保留共享 Dispatcher、连接池、TLS 与超时配置。 */
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** 文本与 JSON 均返回受限原文，共用固定请求头、状态、同源跳转和取消边界。 */
    override suspend fun getText(
        /** 已经通过 Provider URL 策略的请求地址。 */
        url: String,
        /** 固定公开请求头，不进入日志或其他持久状态。 */
        headers: Map<String, String>,
    ): String = getJson(url, headers)

    /** JSON 与文本共用相同网络安全边界，并由 OkHttp 校验固定请求头名称和值。 */
    override suspend fun getJson(url: String, headers: Map<String, String>): String {
        /** Headers.Builder 统一拒绝非法名称或换行值，避免字符串拼接请求头。 */
        val headerBuilder = okhttp3.Headers.Builder()
        /** name 和 value 是调用方明确提供的固定头字段，只交给 OkHttp 校验与编码。 */
        headers.forEach { (name, value) -> headerBuilder.add(name, value) }
        /** 请求只替换显式 header，后续同来源重定向继续由统一 Request 编排保留。 */
        val request = newRequest(url).newBuilder().headers(headerBuilder.build()).build()
        return executeText(request)
    }

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
        /** 独立 Call 的取消、响应关闭和恢复竞争由 executeInCallback 统一处理。 */
        val call = client.newCall(newRequest(url))
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

    /** 显式编排同来源 HTTPS 跳转，并在每次创建后续 Call 前重新检查取消与来源边界。 */
    private suspend fun executeText(initialRequest: Request): String {
        /** 首次请求 URL 固定整条跳转链的 scheme、host 与有效 port 来源。 */
        val originUrl = initialRequest.url
        /** 已访问请求身份包含方法和 URL，用于在真实重复请求发出前拒绝循环。 */
        val visitedRequests = mutableSetOf(
            RedirectRequestIdentity(method = initialRequest.method, url = originUrl),
        )
        /** 当前待执行请求会按受支持的重定向语义逐次替换。 */
        var currentRequest = initialRequest
        /** 已实际跟随的重定向次数，最大值与原 OkHttp follow-up 上限一致。 */
        var redirectCount = 0
        while (true) {
            /** 响应关闭后若调用协程已取消，不得创建下一次 Call。 */
            currentCoroutineContext().ensureActive()
            /** 当前跳转阶段唯一 Call，取消会打断连接或正在读取的最终正文。 */
            val call = client.newCall(currentRequest)
            /** 单次响应只返回受限 outcome，response 总在进入下一跳前关闭。 */
            val outcome = executeInCallback(call) { response ->
                if (response.code.isFollowableRedirectStatus()) {
                    TextResponseOutcome.Redirect(
                        statusCode = response.code,
                        location = response.header("Location"),
                    )
                } else {
                    response.requireAcceptedStatus(allowRedirect = false)
                    /** 空 body 按空字符串交给 Provider 决定解析语义。 */
                    val body = response.body ?: return@executeInCallback TextResponseOutcome.Success("")
                    TextResponseOutcome.Success(body.readLimitedText())
                }
            }
            when (outcome) {
                /** 最终正文已完成限制校验，可直接恢复 Provider 调用。 */
                is TextResponseOutcome.Success -> return outcome.text
                /** 跳转决策只使用初始来源、状态码和 Location，不消费跳转正文。 */
                is TextResponseOutcome.Redirect -> {
                    currentRequest = buildRedirectRequest(
                        request = currentRequest,
                        originUrl = originUrl,
                        outcome = outcome,
                        visitedRequests = visitedRequests,
                        redirectCount = redirectCount,
                    )
                    redirectCount += 1
                }
            }
        }
    }

    /** 根据固定来源与标准方法语义构造下一跳请求；任何不安全输入都以无正文异常拒绝。 */
    private fun buildRedirectRequest(
        /** 刚完成响应的请求，用于继承方法、header 与固定请求体。 */
        request: Request,
        /** 首次请求来源，整条链路不得扩大到其他 scheme、host 或有效 port。 */
        originUrl: okhttp3.HttpUrl,
        /** 当前跳转状态及未信任 Location。 */
        outcome: TextResponseOutcome.Redirect,
        /** 已访问请求身份集合，方法转换后的下一跳加入失败才表示真实循环。 */
        visitedRequests: MutableSet<RedirectRequestIdentity>,
        /** 此前已实际跟随次数，达到 20 后不再创建 Call。 */
        redirectCount: Int,
    ): Request {
        if (redirectCount >= MAX_REDIRECT_FOLLOW_UPS) throw HttpDeniedException(outcome.statusCode)
        /** 相对 Location 必须基于当前响应请求解析，非法或缺失值稳定拒绝。 */
        val redirectUrl = outcome.location?.let(request.url::resolve) ?: throw HttpDeniedException(outcome.statusCode)
        if (redirectUrl.username.isNotEmpty() || redirectUrl.password.isNotEmpty()) {
            throw HttpDeniedException(outcome.statusCode)
        }
        /** HttpUrl.port 已是有效端口，可统一处理显式与默认端口写法。 */
        val isSameOrigin = redirectUrl.scheme == originUrl.scheme &&
            redirectUrl.host == originUrl.host &&
            redirectUrl.port == originUrl.port
        if (!isSameOrigin) throw HttpDeniedException(outcome.statusCode)
        /** 请求构造器复用已编码 URL、header 与必要 body，避免重新拼接敏感字段。 */
        val requestBuilder = request.newBuilder().url(redirectUrl)
        /** POST 的 301/302/303 按浏览器兼容语义切换 GET，其余方法与 307/308 保持。 */
        val shouldSwitchPostToGet = request.method == "POST" &&
            (outcome.statusCode == 301 || outcome.statusCode == 302 || outcome.statusCode == 303)
        if (shouldSwitchPostToGet) {
            requestBuilder
                .method("GET", null)
                .removeHeader("Content-Length")
                .removeHeader("Content-Type")
                .removeHeader("Transfer-Encoding")
        }
        /** 完成方法转换后的下一请求才是循环判断所需的真实网络身份。 */
        val redirectedRequest = requestBuilder.build()
        /** 下一跳身份不包含 body/header，避免保存敏感字段且足以识别当前重放循环。 */
        val redirectedIdentity = RedirectRequestIdentity(
            method = redirectedRequest.method,
            url = redirectedRequest.url,
        )
        if (!visitedRequests.add(redirectedIdentity)) throw HttpDeniedException(outcome.statusCode)
        return redirectedRequest
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

        /** 单次普通请求最多跟随 20 个同来源跳转，与原 OkHttp follow-up 上限保持一致。 */
        private const val MAX_REDIRECT_FOLLOW_UPS = 20

        /** 建立共享客户端，只配置 Dispatcher 和超时且不执行网络。 */
        fun createOkHttpClient(): OkHttpClient {
            /** Dispatcher 固定整个功能最大并发 64、同 host 5。 */
            val dispatcher = Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 5
            }
            return OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10L, TimeUnit.SECONDS)
                .readTimeout(30L, TimeUnit.SECONDS)
                .build()
        }
    }
}

/** 只允许普通请求显式处理 301/302/303/307/308，其他 3xx 仍走统一拒绝分类。 */
private fun Int.isFollowableRedirectStatus(): Boolean =
    this == 301 || this == 302 || this == 303 || this == 307 || this == 308

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
