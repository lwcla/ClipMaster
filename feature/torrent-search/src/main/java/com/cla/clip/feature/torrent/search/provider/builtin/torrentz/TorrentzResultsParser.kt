package com.cla.clip.feature.torrent.search.provider.builtin.torrentz

import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Torrentz 列表中已验证的详情草稿。 */
internal data class TorrentzDraft(
    /** 已验证主站详情 URL。 */ val detailsPageUrl: String,
)

/** Torrentz 列表 DOM parser 与详情合并器。 */
internal class TorrentzResultsParser(
    /** 列表稳定 Provider 身份。 */ private val descriptor: ProviderDescriptor,
    /** 详情 URL 使用的主站 policy。 */ private val siteUrlPolicy: BuiltinProviderUrlPolicy,
    /** DOM 与领域映射计算调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 解析详情草稿，不在 parser 内执行网络。 */
    suspend fun parse(
        /** 受统一大小限制的列表 HTML。 */ html: String,
        /** 已验证列表页基址。 */ pageUrl: String,
    ): List<TorrentzDraft> = withContext(parsingDispatcher) {
        /** rows 是固定 results 容器中的直接 dl。 */
        val rows = Jsoup.parse(html, pageUrl).select("div.results > dl")
        // row 是当前列表候选，协作遍历会在每项前检查父取消。
        rows.mapNotNullCancellable { row -> parseRow(row, pageUrl) }
    }

    /** 只保留具备同站 HTTPS 详情 URL 的草稿。 */
    private fun parseRow(
        /** 当前结果行。 */ row: Element,
        /** 当前列表页 URL。 */ pageUrl: String,
    ): TorrentzDraft? {
        /** detailsCandidate 是固定 dt anchor 的原始 href。 */
        val detailsCandidate = row.selectFirst("dt > a")?.attr("href") ?: return null
        /** detailsPageUrl 越界或无法解析时只跳过当前行。 */
        val detailsPageUrl = runCatching { siteUrlPolicy.resolveOptionalHttpsUrl(pageUrl, detailsCandidate) }
            .getOrNull() ?: return null
        return TorrentzDraft(detailsPageUrl)
    }

    /** Torrentz 结果字段全部来源于合法详情，只补列表详情地址和 Provider 身份。 */
    suspend fun merge(
        /** 来源列表草稿。 */ draft: TorrentzDraft,
        /** 完整合法详情。 */ details: TorrentDetails,
    ): Torrent = withContext(parsingDispatcher) {
        Torrent(
            infoHash = details.infoHash,
            name = details.name,
            size = details.size,
            seeders = details.seeders,
            peers = details.peers,
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = details.uploadEpochMillis,
            category = details.category,
            descriptionPageUrl = draft.detailsPageUrl,
            magnetUri = details.magnetUri,
            fileDownloadLink = details.fileDownloadLink,
        )
    }
}
