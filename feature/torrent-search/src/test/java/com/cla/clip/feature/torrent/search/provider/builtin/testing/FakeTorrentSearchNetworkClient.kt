package com.cla.clip.feature.torrent.search.provider.builtin.testing

import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient

/**
 * Provider JVM 测试使用的可控网络边界；只记录调用地址并执行合成回调，
 * 不创建 OkHttp 或访问真实网络。
 */
internal class FakeTorrentSearchNetworkClient(
    /** GET 文本的合成响应逻辑；默认返回空文本便于单一边界测试。 */
    private val textHandler: suspend (String) -> String = { "" },
    /** 带固定头的文本回调，默认复用无头 handler 保持已有来源测试行为。 */
    private val textWithHeadersHandler: suspend (String, Map<String, String>) -> String = { url, _ -> textHandler(url) },
    /** GET JSON 的无 header 合成响应逻辑；默认返回空数组。 */
    private val jsonHandler: suspend (String) -> String = { "[]" },
    /** 带固定 header 的 GET JSON 合成响应逻辑；默认回退同一 JSON handler。 */
    private val jsonWithHeadersHandler: suspend (String, Map<String, String>) -> String = { url, _ -> jsonHandler(url) },
    /** POST JSON 的合成响应逻辑；本批 Provider 不使用。 */
    private val postJsonHandler: suspend (String, String) -> String = { _, _ -> "" },
    /** 表单提交的合成响应逻辑；本批 Provider 不使用。 */
    private val formHandler: suspend (String, Map<String, String>) -> String = { _, _ -> "" },
    /** 重定向 Location 的合成响应逻辑；只有 AniRena 使用。 */
    private val redirectHandler: suspend (String) -> String? = { null },
) : TorrentSearchNetworkClient {
    /** 已请求的文本 URL，用于断言编码和请求前拒绝边界。 */
    val textUrls = mutableListOf<String>()
    /** 已请求的 JSON URL，用于断言 AniLibria release 上限。 */
    val jsonUrls = mutableListOf<String>()
    /** 已提交的 JSON URL，保留完整接口行为但不记录正文。 */
    val postJsonUrls = mutableListOf<String>()
    /** 已提交的表单 URL，保留完整接口行为但不记录字段。 */
    val formUrls = mutableListOf<String>()
    /** 已请求的重定向 URL，用于验证恶意 host 在网络前被拒绝。 */
    val redirectUrls = mutableListOf<String>()

    /** 记录 URL 后返回合成 HTML。 */
    override suspend fun getText(
        /** 已经通过 Provider URL 策略的请求地址。 */
        url: String,
        /** 固定公开请求头，不进入日志或其他持久状态。 */
        headers: Map<String, String>,
    ): String {
        textUrls += url
        return if (headers.isEmpty()) textHandler(url) else textWithHeadersHandler(url, headers)
    }

    /** 记录 URL 后返回合成 JSON；header 直接交给受控测试回调且不额外持久化。 */
    override suspend fun getJson(url: String, headers: Map<String, String>): String {
        jsonUrls += url
        return if (headers.isEmpty()) jsonHandler(url) else jsonWithHeadersHandler(url, headers)
    }

    /** 只记录 URL，正文直接交给合成回调以避免测试诊断泄漏。 */
    override suspend fun postJson(url: String, json: String): String {
        postJsonUrls += url
        return postJsonHandler(url, json)
    }

    /** 只记录 URL，表单字段直接交给合成回调。 */
    override suspend fun submitForm(url: String, fields: Map<String, String>): String {
        formUrls += url
        return formHandler(url, fields)
    }

    /** 记录经 Provider 白名单校验后的跳转地址，再返回合成 Location。 */
    override suspend fun redirectLocation(url: String): String? {
        redirectUrls += url
        return redirectHandler(url)
    }
}

/** 读取 classpath 下的 UTF-8 最小 fixture；缺失时立即失败以给出可行动诊断。 */
internal fun readProviderFixture(path: String): String {
    /** 当前测试类加载器，用于访问 src/test/resources。 */
    val classLoader = checkNotNull(Thread.currentThread().contextClassLoader)
    /** fixture 输入流在方法结束前关闭，不保留本机路径。 */
    val input = checkNotNull(classLoader.getResourceAsStream(path)) { "合成 Provider fixture 缺失" }
    /** reader 只在 use 块内消费 fixture，方法返回前自动关闭。 */
    return input.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
}
