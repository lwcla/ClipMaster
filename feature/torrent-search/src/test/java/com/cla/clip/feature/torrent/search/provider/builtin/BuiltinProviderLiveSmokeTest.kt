package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.network.CloudflareLockedException
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.network.OkHttpTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.network.ResponseTooLargeException
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.IOException

/** 按显式系统属性启用的 43 Provider 真实联网健康冒烟；普通离线单测不会联网。 */
class BuiltinProviderLiveSmokeTest {
    /** 生成只含 Provider ID、状态、数量和耗时的低敏 JSON 报告，并阻断未分类代码异常。 */
    @Test
    fun allBuiltinProvidersProduceLowSensitiveHealthReport() = runBlocking {
        /** enabled 允许 Gradle 系统属性或继承到 Test JVM 的环境变量显式开启真实联网。 */
        val enabled = System.getProperty(ENABLE_PROPERTY).toBoolean() || System.getenv(ENABLE_ENVIRONMENT).toBoolean()
        if (!enabled) return@runBlocking
        /** networkClient 是使用生产超时、重定向和响应上限的真实 OkHttp 边界。 */
        val networkClient = OkHttpTorrentSearchNetworkClient(OkHttpTorrentSearchNetworkClient.createOkHttpClient())
        /** providers 是固定顺序的 43 个内置 Provider。 */
        val providers = BuiltinProviderRegistry.create(networkClient)
        /** healthEntries 并发执行，避免串行站点超时把发布冒烟拉长到几十分钟。 */
        val healthEntries = providers.map { provider ->
            async(Dispatchers.IO) {
                /** startedAtNanos 只用于计算单站耗时，不写入墙钟或用户信息。 */
                val startedAtNanos = System.nanoTime()
                try {
                    /** results 只读取数量，不把标题、URL、magnet 或响应写入报告。 */
                    val results = withTimeout(PROVIDER_TIMEOUT_MILLIS) { provider.search(SMOKE_QUERY, Category.ALL) }
                    ProviderHealthEntry(provider.descriptor.id.value, "ok", results.size, elapsedMillis(startedAtNanos))
                }
                /** Provider 超过单站上限时记录稳定 timeout，不把协程异常正文写入报告。 */
                catch (failure: TimeoutCancellationException) {
                    ProviderHealthEntry(provider.descriptor.id.value, "timeout", 0, elapsedMillis(startedAtNanos))
                }
                /** Cloudflare 挑战是外站健康状态，只保留可选 HTTP 状态码。 */
                catch (failure: CloudflareLockedException) {
                    ProviderHealthEntry(provider.descriptor.id.value, "cloudflare_locked", 0, elapsedMillis(startedAtNanos), failure.statusCode)
                }
                /** HTTP 拒绝只记录低敏状态，不记录目标或响应正文。 */
                catch (failure: HttpDeniedException) {
                    ProviderHealthEntry(provider.descriptor.id.value, "http_denied", 0, elapsedMillis(startedAtNanos), failure.statusCode)
                }
                /** 超限响应作为稳定容量状态报告。 */
                catch (failure: ResponseTooLargeException) {
                    ProviderHealthEntry(provider.descriptor.id.value, "response_too_large", 0, elapsedMillis(startedAtNanos))
                }
                /** 站点结构漂移映射为解析状态，不输出 fixture 或第三方正文。 */
                catch (failure: ProviderParseException) {
                    ProviderHealthEntry(provider.descriptor.id.value, "parse_failed", 0, elapsedMillis(startedAtNanos))
                }
                /** DNS、TLS 和连接错误统一为网络状态，不输出异常 message。 */
                catch (failure: IOException) {
                    ProviderHealthEntry(provider.descriptor.id.value, "network_failed", 0, elapsedMillis(startedAtNanos))
                }
                /** 未分类异常只保留类型名并在报告写出后阻断门禁。 */
                catch (failure: Throwable) {
                    ProviderHealthEntry(provider.descriptor.id.value, "code_failure", 0, elapsedMillis(startedAtNanos), failureType = failure::class.java.simpleName)
                }
            }
        }.awaitAll()
        /** reportFile 是调用方指定或模块 build 目录下的低敏健康报告。 */
        /** reportPath 优先使用系统属性，其次使用环境变量，最后回落到模块 build 报告目录。 */
        val reportPath = System.getProperty(REPORT_PROPERTY)
            ?: System.getenv(REPORT_ENVIRONMENT)
            ?: DEFAULT_REPORT_PATH
        /** reportFile 将低敏结果写入发布门禁指定位置。 */
        val reportFile = File(reportPath)
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(healthEntries.toJsonReport(), Charsets.UTF_8)
        /** codeFailures 只保留 Provider ID 和异常类型，用于阻断未分类代码错误。 */
        val codeFailures = healthEntries.filter { entry -> entry.status == "code_failure" }
        assertEquals("真实 Provider 冒烟出现未分类代码异常：$codeFailures", emptyList<ProviderHealthEntry>(), codeFailures)
        assertEquals(43, healthEntries.size)
    }

    /** 使用单调时钟计算单站耗时，避免系统时间调整造成负值。 */
    private fun elapsedMillis(startedAtNanos: Long): Long {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L
    }

    /** 把低敏健康条目编码为稳定 JSON，不依赖反射序列化或业务响应模型。 */
    private fun List<ProviderHealthEntry>.toJsonReport(): String {
        /** entriesJson 是按固定注册顺序排列的 JSON 对象列表。 */
        val entriesJson = joinToString(",\n") { entry ->
            /** statusCodeJson 只输出可选 HTTP 状态，不输出 URL 或响应。 */
            val statusCodeJson = entry.httpStatus?.toString() ?: "null"
            /** failureTypeJson 只输出未分类异常类型名，绝不输出异常 message 或堆栈。 */
            val failureTypeJson = entry.failureType?.let { type -> "\"$type\"" } ?: "null"
            """  {"provider_id":"${entry.providerId}","status":"${entry.status}","result_count":${entry.resultCount},"duration_ms":${entry.durationMillis},"http_status":$statusCodeJson,"failure_type":$failureTypeJson}"""
        }
        return "{\n\"provider_count\":$size,\n\"entries\":[\n$entriesJson\n]}\n"
    }

    /** 真实联网健康报告中的单站低敏条目。 */
    private data class ProviderHealthEntry(
        /** 稳定 Provider ID。 */
        val providerId: String,
        /** 固定健康分类，不包含异常正文。 */
        val status: String,
        /** 成功响应的结果数量，失败时为 0。 */
        val resultCount: Int,
        /** 单站调用耗时毫秒数。 */
        val durationMillis: Long,
        /** 可选 HTTP 状态码。 */
        val httpStatus: Int? = null,
        /** 仅未分类代码异常使用的类型名，不包含 message。 */
        val failureType: String? = null,
    )

    /** 真实冒烟的稳定开关、输出和边界常量。 */
    companion object {
        /** 只有显式设为 true 才访问互联网。 */
        private const val ENABLE_PROPERTY = "torrentSearchLiveSmoke"
        /** PowerShell 发布命令通过环境变量把开关可靠传入隔离的 Gradle Test JVM。 */
        private const val ENABLE_ENVIRONMENT = "TORRENT_SEARCH_LIVE_SMOKE"
        /** 可由发布脚本指定报告文件位置。 */
        private const val REPORT_PROPERTY = "torrentSearchLiveSmokeReport"
        /** 环境变量形式的报告路径用于避免 Gradle 不转发任意系统属性。 */
        private const val REPORT_ENVIRONMENT = "TORRENT_SEARCH_LIVE_SMOKE_REPORT"
        /** 未指定时写入模块 build 报告目录。 */
        private const val DEFAULT_REPORT_PATH = "build/reports/torrent-search/provider-live-smoke.json"
        /** 中性且能被多数综合索引理解的固定测试词，不来自用户输入。 */
        private const val SMOKE_QUERY = "linux"
        /** 单站上限略高于生产协调器的 45 秒预算，用于包含测试调度开销。 */
        private const val PROVIDER_TIMEOUT_MILLIS = 50_000L
    }
}
