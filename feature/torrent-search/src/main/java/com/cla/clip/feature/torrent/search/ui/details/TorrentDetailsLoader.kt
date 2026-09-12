package com.cla.clip.feature.torrent.search.ui.details

import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.logging.ProviderLogEvent
import com.cla.clip.feature.torrent.search.logging.ProviderLogOperation
import com.cla.clip.feature.torrent.search.logging.ProviderLogStage
import com.cla.clip.feature.torrent.search.logging.ProviderLogger
import com.cla.clip.feature.torrent.search.model.contract.ProviderFailureReason
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 详情请求失败的固定低敏分类。 */
internal enum class TorrentDetailsFailureReason {
    /** 候选 URL 或稳定身份不合法。 */
    INVALID_REQUEST,
    /** 选中来源不存在或不具备详情能力。 */
    UNSUPPORTED_PROVIDER,
    /** Provider 未返回详情。 */
    UNAVAILABLE,
    /** 返回对象与候选身份不一致。 */
    IDENTITY_MISMATCH,
    /** 网络、解析或超时失败。 */
    LOAD_FAILED,
}

/** 详情加载的封闭结果。 */
internal sealed interface TorrentDetailsLoadResult {
    /** 已通过身份校验的详情。 */
    data class Success(
        /** 可安全交给 UI 的领域详情。 */
        val details: TorrentDetails,
    ) : TorrentDetailsLoadResult

    /** 不携带异常正文的失败。 */
    data class Failure(
        /** 稳定失败分类。 */
        val reason: TorrentDetailsFailureReason,
    ) : TorrentDetailsLoadResult
}

/** 详情能力选择、请求安全与响应身份校验协作者。 */
internal class TorrentDetailsLoader(
    /** 单次详情网络、解析和映射总上限。 */
    private val providerTimeoutMs: Long = DEFAULT_PROVIDER_TIMEOUT_MS,
    /** 详情请求的固定白名单日志出口。 */
    private val logger: ProviderLogger = ProviderLogger { },
) {
    /** 加载选中候选的原生详情。 */
    suspend fun load(
        /** 搜索或浏览页保留在内存中的候选。 */
        candidate: Torrent,
        /** 固定注册表中的全部来源。 */
        providers: List<SearchProvider>,
    ): TorrentDetailsLoadResult {
        /** provider 必须由候选稳定 ID 精确选择，不能按展示名或 URL 猜测。 */
        val provider = providers.firstOrNull { /** registered 是注册表当前候选。 */ registered ->
            registered.descriptor.id == candidate.providerId
        } as? TorrentDetailsProvider ?: return TorrentDetailsLoadResult.Failure(TorrentDetailsFailureReason.UNSUPPORTED_PROVIDER)
        /** detailsUrl 是候选中唯一允许发起请求的详情地址。 */
        val detailsUrl = candidate.descriptionPageUrl
            ?.takeIf { /** rawUrl 是待检查长度的不可信地址。 */ rawUrl -> rawUrl.length <= MAX_URL_CHARACTERS }
            ?.toHttpUrlOrNull()
            ?: return TorrentDetailsLoadResult.Failure(TorrentDetailsFailureReason.INVALID_REQUEST)
        /** allowedHosts 来自固定 Provider 元数据，不采纳正文或重定向输入。 */
        val allowedHosts = buildAllowedHosts(provider)
        if (!detailsUrl.isSafeDetailsUrl(allowedHosts)) {
            return TorrentDetailsLoadResult.Failure(TorrentDetailsFailureReason.INVALID_REQUEST)
        }
        /** startTimeMs 只用于详情请求本地耗时统计。 */
        val startTimeMs = System.currentTimeMillis()
        log(provider.descriptor.id, ProviderLogStage.STARTED, null, null)
        return try {
            /** details 是固定超时内返回的可选详情模型。 */
            val details = withTimeout(providerTimeoutMs) { provider.getDetails(detailsUrl.toString()) }
            if (details == null) {
                log(provider.descriptor.id, ProviderLogStage.FAILED, System.currentTimeMillis() - startTimeMs, ProviderFailureReason.PARSE_FAILURE)
                return TorrentDetailsLoadResult.Failure(TorrentDetailsFailureReason.UNAVAILABLE)
            }
            if (details.providerId != candidate.providerId || !details.infoHash.equals(candidate.infoHash, ignoreCase = true)) {
                log(provider.descriptor.id, ProviderLogStage.FAILED, System.currentTimeMillis() - startTimeMs, ProviderFailureReason.PARSE_FAILURE)
                TorrentDetailsLoadResult.Failure(TorrentDetailsFailureReason.IDENTITY_MISMATCH)
            } else {
                log(provider.descriptor.id, ProviderLogStage.COMPLETED, System.currentTimeMillis() - startTimeMs, null)
                TorrentDetailsLoadResult.Success(details)
            }
        /** 父取消必须原样传播，页面离开不得转成可见错误。 */
        } catch (timeout: TimeoutCancellationException) {
            log(provider.descriptor.id, ProviderLogStage.FAILED, System.currentTimeMillis() - startTimeMs, ProviderFailureReason.TIMEOUT)
            TorrentDetailsLoadResult.Failure(TorrentDetailsFailureReason.LOAD_FAILED)
        /** 页面离开或候选替换触发的取消必须抵达底层网络。 */
        } catch (cancellation: CancellationException) {
            log(provider.descriptor.id, ProviderLogStage.FAILED, System.currentTimeMillis() - startTimeMs, ProviderFailureReason.CANCELLED)
            throw cancellation
        /** 其他来源失败只映射为固定分类，不回显异常 message。 */
        } catch (failure: Throwable) {
            log(provider.descriptor.id, ProviderLogStage.FAILED, System.currentTimeMillis() - startTimeMs, ProviderFailureReason.UNKNOWN)
            TorrentDetailsLoadResult.Failure(TorrentDetailsFailureReason.LOAD_FAILED)
        }
    }

    /** 输出详情固定字段日志，禁止 URL、infoHash、正文和异常 message。 */
    private fun log(
        /** 选中来源稳定 ID。 */
        providerId: com.cla.clip.feature.torrent.search.provider.ProviderId,
        /** 当前详情阶段。 */
        stage: ProviderLogStage,
        /** 可选本地耗时毫秒。 */
        durationMs: Long?,
        /** 可选稳定失败分类。 */
        reason: ProviderFailureReason?,
    ) {
        logger.log(
            ProviderLogEvent(
                providerId = providerId,
                operation = ProviderLogOperation.DETAILS,
                stage = stage,
                resultCount = if (stage == ProviderLogStage.COMPLETED) 1 else null,
                durationMs = durationMs,
                httpStatusCode = null,
                retried = false,
                reason = reason,
            ),
        )
    }

    /** 从 Provider 固定基础地址与备用 host 建立精确允许集合。 */
    private fun buildAllowedHosts(
        /** 已确认具备详情能力的选中来源。 */
        provider: TorrentDetailsProvider,
    ): Set<String> {
        /** baseHost 是描述符基础 HTTPS 地址的规范 host。 */
        val baseHost = provider.descriptor.baseUrl.toHttpUrlOrNull()?.host
        /** alternateHosts 只接收能作为 HTTPS authority 解析的纯 host。 */
        val alternateHosts = provider.alternateUrlDomains.mapNotNull { /** domain 是固定注册元数据中的备用 host。 */ domain ->
            normalizeHost(domain)
        }
        return buildSet {
            baseHost?.let(::add)
            addAll(alternateHosts)
        }
    }

    /** 把纯 host 元数据规范化为小写 ASCII host，非法形状返回 null。 */
    private fun normalizeHost(
        /** Provider 固定备用 host。 */
        domain: String,
    ): String? {
        if (domain.isBlank() || domain.any { /** character 是待拒绝路径、端口或凭据分隔符。 */ character -> character in "/\\@:?" }) return null
        return "https://$domain".toHttpUrlOrNull()?.host
    }

    /** 校验详情请求只访问白名单 HTTPS 默认端口且不携带 user-info。 */
    private fun HttpUrl.isSafeDetailsUrl(
        /** 当前选中 Provider 的精确 host 白名单。 */
        allowedHosts: Set<String>,
    ): Boolean = scheme == "https" && port == 443 && username.isEmpty() && password.isEmpty() && host in allowedHosts

    /** 详情加载固定安全边界。 */
    companion object {
        /** 与搜索、浏览一致的单 Provider 默认超时。 */
        private const val DEFAULT_PROVIDER_TIMEOUT_MS = 45_000L
        /** 详情候选 URL 最大字符数，超限在联网前拒绝。 */
        private const val MAX_URL_CHARACTERS = 2_048
    }
}
