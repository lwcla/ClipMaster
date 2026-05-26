package com.cla.clip.master.update

import com.cla.clip.base.general.di.LinkPreviewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import kotlin.system.measureTimeMillis

/**
 * 一次检查更新所需的静态配置。
 *
 * 把可尝试的数据源、当前安装包信息和统一兜底发布页集中传入，
 * 避免 ViewModel 自己拼接远端地址和渠道判断。
 */
data class AppUpdateCheckConfig(
    /** 按优先顺序尝试的远端 manifest 数据源。 */
    val manifestSources: List<AppUpdateManifestSource>,

    /** 当前安装包参与版本比较和渠道匹配的稳定信息。 */
    val currentApp: AppUpdateCurrentApp,

    /** 所有数据源都失败时仍可展示给用户的统一发布页入口。 */
    val fallbackReleasePage: AppUpdateLink?,
)

/** 检查更新触发场景；`code` 只用于日志检索，不能随意改名。 */
enum class AppUpdateCheckTrigger(val code: String) {
    /** 用户主动点击“检查更新”。 */
    Manual("manual"),

    /** 页面可见后执行的后台轻量检查。 */
    Auto("auto"),
}

/**
 * App 自升级检查器。
 *
 * 串联远端请求、manifest 解析、数据源回退和失败兜底，不直接持有 UI 状态，
 * 方便 ViewModel 和单元测试复用同一套检查逻辑。
 */
class AppUpdateChecker @Inject constructor(
    /** 远端文本抓取器；生产环境使用网络实现，测试里可替换成假数据。 */
    private val fetcher: AppUpdateManifestFetcher,

    /** manifest 纯逻辑解析器；包名、渠道、版本和下载源排序规则都由它负责。 */
    private val parser: AppUpdateManifestParser,

    /** 更新链路专用日志器，统一限制日志只输出低敏字段。 */
    private val logger: AppUpdateLogger,
) {
    /**
     * 依次尝试所有数据源并返回最终结果。
     *
     * 任一数据源只要返回“有更新”或“已最新”就立即结束；
     * 只有所有源都失败时才返回最后一次失败结果。
     */
    suspend fun check(
        config: AppUpdateCheckConfig,
        trigger: AppUpdateCheckTrigger,
        forceCheck: Boolean,
        throttleHit: Boolean,
    ): AppUpdateCheckResult {
        logger.logCheckStart(trigger, config.currentApp.versionCode, forceCheck, throttleHit)
        /** 全部数据源都失败时返回的最后一个失败结果。 */
        var lastFailure: AppUpdateCheckResult.Failed? = null
        /** 当前可尝试的数据源列表；为空时直接返回无下载源。 */
        val sources = config.manifestSources.ifEmpty {
            return AppUpdateCheckResult.Failed(
                reason = AppUpdateFailureReason.NoDownloadSource,
                fallbackReleasePage = config.fallbackReleasePage,
            )
        }
        sources.forEachIndexed { index, source ->
            when (val result = checkSource(source, config)) {
                is AppUpdateCheckResult.UpdateAvailable,
                is AppUpdateCheckResult.UpToDate -> return result

                is AppUpdateCheckResult.Failed -> {
                    lastFailure = result
                    if (index < sources.lastIndex) {
                        logger.logSourceFallback(source.id, result.reason, result.statusCode)
                    }
                }
            }
        }
        return lastFailure ?: AppUpdateCheckResult.Failed(
            reason = AppUpdateFailureReason.UnknownError,
            fallbackReleasePage = config.fallbackReleasePage,
        )
    }

    /**
     * 检查单个数据源。
     *
     * 这里把 GitHub 直接 `update.json` 和 Gitee Release 二次抓取流程统一封装，
     * 上层只关心成功、已最新还是失败。
     */
    private suspend fun checkSource(
        source: AppUpdateManifestSource,
        config: AppUpdateCheckConfig,
    ): AppUpdateCheckResult {
        /** 当前数据源的抓取结果；失败分支会统一映射成检查结果。 */
        val fetchResult = fetcher.fetch(source.url)
        return when (fetchResult) {
            is AppUpdateFetchResult.Success -> {
                logger.logFetchSuccess(fetchResult.statusCode, fetchResult.elapsedMillis)
                /** 真正参与解析的 manifest 文本。 */
                val manifestBody = when (source.format) {
                    AppUpdateManifestFormat.DirectJson -> fetchResult.body
                    AppUpdateManifestFormat.GiteeRelease -> {
                        /** 从 Gitee Release 资产里二次抓取到的 `update.json`。 */
                        val giteeFetchResult = fetchGiteeUpdateJson(fetchResult.body)
                        if (giteeFetchResult != null) {
                            giteeFetchResult
                        } else {
                            return AppUpdateCheckResult.Failed(
                                reason = AppUpdateFailureReason.NoDownloadSource,
                                fallbackReleasePage = source.releasePage ?: config.fallbackReleasePage,
                            )
                        }
                    }
                }
                parser.parse(manifestBody, config.currentApp).also { result ->
                    logger.logParseResult(result, config.currentApp.channel)
                }.withFallback(source.releasePage ?: config.fallbackReleasePage)
            }

            is AppUpdateFetchResult.HttpError -> {
                logger.logHttpError(fetchResult.statusCode, fetchResult.elapsedMillis)
                AppUpdateCheckResult.Failed(
                    reason = AppUpdateFailureReason.HttpError,
                    statusCode = fetchResult.statusCode,
                    fallbackReleasePage = source.releasePage ?: config.fallbackReleasePage,
                )
            }

            is AppUpdateFetchResult.NetworkError -> {
                logger.logNetworkError(fetchResult.throwable, fetchResult.elapsedMillis)
                AppUpdateCheckResult.Failed(
                    reason = AppUpdateFailureReason.NetworkError,
                    fallbackReleasePage = source.releasePage ?: config.fallbackReleasePage,
                )
            }

            is AppUpdateFetchResult.UnknownError -> {
                logger.logUnknownError(fetchResult.throwable, fetchResult.elapsedMillis)
                AppUpdateCheckResult.Failed(
                    reason = AppUpdateFailureReason.UnknownError,
                    fallbackReleasePage = source.releasePage ?: config.fallbackReleasePage,
                )
            }
        }
    }

    /**
     * 从 Gitee Release 响应里解析并再次抓取 `update.json`。
     *
     * Gitee 最新发布接口返回的是 release 详情，不是最终 manifest，
     * 因此需要先定位 `update.json` 资产，再执行第二次请求。
     */
    private suspend fun fetchGiteeUpdateJson(releaseBody: String): String? {
        /** Gitee Release 资产里的 `update.json` 地址；取不到就放弃当前源。 */
        val updateJsonUrl = parser.extractGiteeUpdateJsonUrl(releaseBody) ?: return null
        return when (val fetchResult = fetcher.fetch(updateJsonUrl)) {
            is AppUpdateFetchResult.Success -> {
                logger.logFetchSuccess(fetchResult.statusCode, fetchResult.elapsedMillis)
                fetchResult.body
            }

            is AppUpdateFetchResult.HttpError -> {
                logger.logHttpError(fetchResult.statusCode, fetchResult.elapsedMillis)
                null
            }

            is AppUpdateFetchResult.NetworkError -> {
                logger.logNetworkError(fetchResult.throwable, fetchResult.elapsedMillis)
                null
            }

            is AppUpdateFetchResult.UnknownError -> {
                logger.logUnknownError(fetchResult.throwable, fetchResult.elapsedMillis)
                null
            }
        }
    }

    /** 给失败结果补上统一兜底发布页，避免某个数据源漏配发布页时 UI 无法给出下载入口。 */
    private fun AppUpdateCheckResult.withFallback(fallback: AppUpdateLink?): AppUpdateCheckResult {
        return if (this is AppUpdateCheckResult.Failed && fallbackReleasePage == null) {
            copy(fallbackReleasePage = fallback)
        } else {
            this
        }
    }
}

/** 远端 manifest 文本抓取契约。 */
interface AppUpdateManifestFetcher {
    /** 根据 URL 拉取文本并统一包装为抓取结果。 */
    suspend fun fetch(url: String): AppUpdateFetchResult
}

/** 远端文本抓取结果；所有分支都带上耗时，方便后续日志排障。 */
sealed interface AppUpdateFetchResult {
    /** 成功拿到远端文本和响应码。 */
    data class Success(
        /** 原始响应体文本，后续交给解析器消费。 */
        val body: String,

        /** 服务端返回的 HTTP 状态码。 */
        val statusCode: Int,

        /** 本次请求总耗时，单位毫秒。 */
        val elapsedMillis: Long,
    ) : AppUpdateFetchResult

    /** 服务端返回了非 2xx 状态码。 */
    data class HttpError(
        /** 失败响应的 HTTP 状态码。 */
        val statusCode: Int,

        /** 本次请求总耗时，单位毫秒。 */
        val elapsedMillis: Long,
    ) : AppUpdateFetchResult

    /** 网络层出现明确的 IO 异常，例如离线、超时或连接失败。 */
    data class NetworkError(
        /** 原始 IO 异常对象，供日志记录堆栈。 */
        val throwable: IOException,

        /** 本次请求总耗时，单位毫秒。 */
        val elapsedMillis: Long,
    ) : AppUpdateFetchResult

    /** 非 IO 类型的意外失败，例如响应体读取或运行时异常。 */
    data class UnknownError(
        /** 原始异常对象，用于低频诊断。 */
        val throwable: Throwable,

        /** 本次请求总耗时，单位毫秒。 */
        val elapsedMillis: Long,
    ) : AppUpdateFetchResult
}

/** 基于 OkHttp 的 manifest 抓取实现。 */
class OkHttpAppUpdateManifestFetcher @Inject constructor(
    @param:LinkPreviewClient
    /** 复用现有轻量网页请求客户端配置，避免更新链路再维护一套网络参数。 */
    private val okHttpClient: OkHttpClient,
) : AppUpdateManifestFetcher {
    /**
     * 在 IO 线程抓取远端文本，并把统一耗时回填到最终结果对象。
     *
     * 先记录中间结果、后统一补耗时，能避免成功和失败分支重复维护计时代码。
     */
    override suspend fun fetch(url: String): AppUpdateFetchResult = withContext(Dispatchers.IO) {
        /** 请求执行过程中产生的临时抓取结果。 */
        var result: AppUpdateFetchResult? = null
        /** 本次请求从发起到结束的总耗时，单位毫秒。 */
        val elapsedMillis = measureTimeMillis {
            result = try {
                /** 更新检查只做 GET 请求，不附带额外 Header，尽量保持接口兼容性。 */
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        AppUpdateFetchResult.HttpError(response.code, 0L)
                    } else {
                        AppUpdateFetchResult.Success(response.body.string(), response.code, 0L)
                    }
                }
            } catch (error: IOException) {
                AppUpdateFetchResult.NetworkError(error, 0L)
            } catch (error: Throwable) {
                AppUpdateFetchResult.UnknownError(error, 0L)
            }
        }

        /** 补齐耗时后的最终结果；极端情况下若中间结果为空，则回退成未知错误。 */
        when (val fetchResult = result ?: AppUpdateFetchResult.UnknownError(IllegalStateException("empty result"), elapsedMillis)) {
            is AppUpdateFetchResult.Success -> fetchResult.copy(elapsedMillis = elapsedMillis)
            is AppUpdateFetchResult.HttpError -> fetchResult.copy(elapsedMillis = elapsedMillis)
            is AppUpdateFetchResult.NetworkError -> fetchResult.copy(elapsedMillis = elapsedMillis)
            is AppUpdateFetchResult.UnknownError -> fetchResult.copy(elapsedMillis = elapsedMillis)
        }
    }
}
