package com.cla.clip.feature.torrent.search.ui.browse

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderFailureReason
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.logging.ProviderLogEvent
import com.cla.clip.feature.torrent.search.logging.ProviderLogOperation
import com.cla.clip.feature.torrent.search.logging.ProviderLogStage
import com.cla.clip.feature.torrent.search.logging.ProviderLogger
import com.cla.clip.feature.torrent.search.network.toProviderFailureReason
import com.cla.clip.feature.torrent.search.provider.LatestTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.provider.TopTorrentsProvider
import com.cla.clip.feature.torrent.search.ui.search.TorrentResultSorter
import com.cla.clip.feature.torrent.search.model.contract.SortCriteria
import com.cla.clip.feature.torrent.search.model.contract.SortOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

/** 浏览模式稳定状态，不作为持久化或导航协议。 */
internal enum class BrowseMode {
    /** 按最新上传时间浏览。 */
    LATEST,
    /** 按热门做种数浏览。 */
    TOP,
}

/** 单次浏览聚合结果，只包含低敏失败分类和领域结果。 */
internal data class BrowseLoadResult(
    /** 已去重并排序的结果。 */
    val torrents: List<Torrent>,
    /** 单来源稳定失败分类。 */
    val failures: Map<ProviderId, ProviderFailureReason>,
)

/** Latest/Top 并发协调器，网络只在调用 load 后启动。 */
internal class BrowseCoordinator(
    /** 单 Provider 浏览任务上限，覆盖网络、解析和领域映射。 */
    private val providerTimeoutMs: Long = DEFAULT_PROVIDER_TIMEOUT_MS,
    /** 只接收固定操作、阶段、数量和 reasonCode 的低敏日志出口。 */
    private val logger: ProviderLogger = ProviderLogger { },
) {
    /** 执行一次浏览聚合。 */
    suspend fun load(
        /** 当前 Latest 或 Top 模式。 */
        mode: BrowseMode,
        /** 当前用户选择的分类。 */
        category: Category,
        /** 已经过本机设置筛选的来源。 */
        providers: List<SearchProvider>,
    ): BrowseLoadResult = supervisorScope {
        /** capableProviders 只保留当前模式接口，避免把不支持能力误报为失败。 */
        val capableProviders = providers.filter { /** provider 是待检查浏览接口的已启用来源。 */ provider ->
            when (mode) {
                BrowseMode.LATEST -> provider is LatestTorrentsProvider
                BrowseMode.TOP -> provider is TopTorrentsProvider
            }
        }
        /** providerResults 按注册表顺序等待各独立任务，失败不会取消兄弟来源。 */
        val providerResults = capableProviders.map { /** provider 是当前独立浏览任务。 */ provider ->
            async {
                loadProvider(mode, category, provider)
            }
        }.awaitAll()
        /** failures 汇总稳定 Provider ID 与低敏失败分类。 */
        val failures = linkedMapOf<ProviderId, ProviderFailureReason>()
        /** uniqueTorrents 按来源注册顺序和来源内部顺序保留首个稳定身份。 */
        val uniqueTorrents = linkedMapOf<String, Torrent>()
        providerResults.forEach { /** result 是单来源隔离结果。 */ result ->
            result.failure?.let { /** reason 是当前来源的稳定失败分类。 */ reason ->
                failures[result.providerId] = reason
            }
            result.torrents.forEach { /** torrent 是待按 infoHash 去重的单条结果。 */ torrent ->
                if (uniqueTorrents.size < MAX_RESULTS) uniqueTorrents.putIfAbsent(torrent.infoHash, torrent)
            }
        }
        /** criteria 固定 Latest 日期或 Top 做种数，均使用现有空值稳定排序器。 */
        val criteria = if (mode == BrowseMode.LATEST) SortCriteria.DATE else SortCriteria.SEEDERS
        BrowseLoadResult(
            torrents = TorrentResultSorter.sort(uniqueTorrents.values.toList(), criteria, SortOrder.DESCENDING),
            failures = failures,
        )
    }

    /** 执行单个能力来源并将非取消失败隔离成稳定分类。 */
    private suspend fun loadProvider(
        /** 当前浏览模式。 */
        mode: BrowseMode,
        /** 当前分类快照。 */
        category: Category,
        /** 已确认具备当前模式能力的来源。 */
        provider: SearchProvider,
    ): BrowseProviderResult {
        /** startTimeMs 只用于计算本地耗时。 */
        val startTimeMs = System.currentTimeMillis()
        log(provider.descriptor.id, mode, ProviderLogStage.STARTED, null, null, null)
        return try {
            /** torrents 是单 Provider 在固定超时内返回的完整批次。 */
            val torrents = withTimeout(providerTimeoutMs) {
                when (mode) {
                    BrowseMode.LATEST -> (provider as LatestTorrentsProvider).getLatestTorrents(category)
                    BrowseMode.TOP -> (provider as TopTorrentsProvider).getTopTorrents(category)
                }
            }
            log(provider.descriptor.id, mode, ProviderLogStage.RESULTS, torrents.size, null, null)
            log(provider.descriptor.id, mode, ProviderLogStage.COMPLETED, torrents.size, System.currentTimeMillis() - startTimeMs, null)
            BrowseProviderResult(provider.descriptor.id, torrents, null)
        /** timeout 必须归类为稳定超时，而不能按普通父取消传播。 */
        } catch (timeout: TimeoutCancellationException) {
            log(provider.descriptor.id, mode, ProviderLogStage.FAILED, null, System.currentTimeMillis() - startTimeMs, ProviderFailureReason.TIMEOUT)
            BrowseProviderResult(provider.descriptor.id, emptyList(), ProviderFailureReason.TIMEOUT)
        /** cancellation 表示页面离开或切换模式，必须继续向下和向上传播。 */
        } catch (cancellation: CancellationException) {
            log(provider.descriptor.id, mode, ProviderLogStage.FAILED, null, System.currentTimeMillis() - startTimeMs, ProviderFailureReason.CANCELLED)
            throw cancellation
        /** failure 只隔离当前 Provider，禁止把异常 message 带入结果或日志。 */
        } catch (failure: Throwable) {
            /** reason 把解析异常与网络稳定分类统一映射。 */
            val reason = if (failure is ProviderParseException) ProviderFailureReason.PARSE_FAILURE else failure.toProviderFailureReason()
            log(provider.descriptor.id, mode, ProviderLogStage.FAILED, null, System.currentTimeMillis() - startTimeMs, reason)
            BrowseProviderResult(provider.descriptor.id, emptyList(), reason)
        }
    }

    /** 输出 Browse 固定白名单日志字段，禁止 URL、分类正文或结果身份进入事件。 */
    private fun log(
        /** 当前来源稳定 ID。 */
        providerId: ProviderId,
        /** 当前 Latest/Top 模式。 */
        mode: BrowseMode,
        /** 当前生命周期阶段。 */
        stage: ProviderLogStage,
        /** 可选结果数量。 */
        resultCount: Int?,
        /** 可选耗时毫秒。 */
        durationMs: Long?,
        /** 可选固定失败分类。 */
        reason: ProviderFailureReason?,
    ) {
        /** operation 把封闭 Browse 模式映射为日志稳定 code。 */
        val operation = if (mode == BrowseMode.LATEST) ProviderLogOperation.LATEST else ProviderLogOperation.TOP
        logger.log(
            ProviderLogEvent(
                providerId = providerId,
                operation = operation,
                stage = stage,
                resultCount = resultCount,
                durationMs = durationMs,
                httpStatusCode = null,
                retried = false,
                reason = reason,
            ),
        )
    }

    /** 浏览协调器固定资源边界。 */
    companion object {
        /** 单 Provider 默认超时与搜索协调器保持一致。 */
        private const val DEFAULT_PROVIDER_TIMEOUT_MS = 45_000L
        /** 单页聚合最多保留 200 个稳定身份，防止异常来源放大内存。 */
        private const val MAX_RESULTS = 200
    }
}

/** 单 Provider 的内部隔离结果，避免异常对象进入页面状态。 */
private data class BrowseProviderResult(
    /** 来源稳定 ID。 */
    val providerId: ProviderId,
    /** 当前来源的领域结果。 */
    val torrents: List<Torrent>,
    /** 可选稳定失败分类。 */
    val failure: ProviderFailureReason?,
)
