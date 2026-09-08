package com.cla.clip.feature.torrent.search.provider.builtin.torrentdownloads

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** TorrentDownloads 列表中已验证的详情草稿与可选回退字段。 */
internal data class TorrentDownloadsDraft(
    /** 已验证主站详情 URL。 */ val detailsPageUrl: String,
    /** 列表可选展示大小。 */ val fallbackSize: String?,
    /** 列表可选做种数。 */ val fallbackSeeders: UInt?,
    /** 列表可选节点数。 */ val fallbackPeers: UInt?,
    /** 列表可选分类。 */ val fallbackCategory: Category?,
)

/** TorrentDownloads 列表 DOM parser 与详情合并器。 */
internal class TorrentDownloadsResultsParser(
    /** 列表稳定 Provider 身份。 */ private val descriptor: ProviderDescriptor,
    /** 详情 URL 使用的主站 policy。 */ private val siteUrlPolicy: BuiltinProviderUrlPolicy,
    /** DOM 与领域映射计算调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 跳过固定两个 header 行并解析安全详情草稿。 */
    suspend fun parse(
        /** 受统一大小限制的列表 HTML。 */ html: String,
        /** 已验证列表页基址。 */ pageUrl: String,
    ): List<TorrentDownloadsDraft> = withContext(parsingDispatcher) {
        /** container 只使用固定上游最后一个 inner_container。 */
        val container = Jsoup.parse(html, pageUrl).select("div.inner_container").lastOrNull()
            ?: return@withContext emptyList()
        /** rows 删除固定两个非数据头部。 */
        val rows = container.select("div.grey_bar3").drop(2)
        // row 是当前列表候选，协作遍历会在每项前检查父取消。
        rows.mapNotNullCancellable { row -> parseRow(row, pageUrl) }
    }

    /** 详情 URL 必填，列表字段只作为详情缺失时的回退。 */
    private fun parseRow(
        /** 当前结果行。 */ row: Element,
        /** 当前列表页 URL。 */ pageUrl: String,
    ): TorrentDownloadsDraft? {
        /** detailsCandidate 来自第一段落的第二个 anchor。 */
        val detailsCandidate = row.selectFirst("p:nth-child(1) > a:nth-child(2)")?.attr("href") ?: return null
        /** detailsPageUrl 必须是精确同站 HTTPS。 */
        val detailsPageUrl = runCatching { siteUrlPolicy.resolveOptionalHttpsUrl(pageUrl, detailsCandidate) }
            .getOrNull() ?: return null
        return TorrentDownloadsDraft(
            detailsPageUrl = detailsPageUrl,
            fallbackSize = row.selectFirst("span:nth-child(5)")?.ownText()?.trim()?.takeIf(String::isNotEmpty),
            fallbackSeeders = row.selectFirst("span:nth-child(4)")?.ownText()?.trim()?.toUIntOrNull(),
            fallbackPeers = row.selectFirst("span:nth-child(3)")?.ownText()?.trim()?.toUIntOrNull(),
            fallbackCategory = row.selectFirst("p:nth-child(1) > img:nth-child(1)")?.attr("src")
                ?.let(TorrentDownloadsCategoryMapper::fromIconUrl),
        )
    }

    /** 用详情必填身份与优先字段生成公开结果。 */
    suspend fun merge(
        /** 来源列表草稿。 */ draft: TorrentDownloadsDraft,
        /** 完整合法详情。 */ details: TorrentDetails,
    ): Torrent = withContext(parsingDispatcher) {
        Torrent(
            infoHash = details.infoHash,
            name = details.name,
            size = details.size ?: draft.fallbackSize,
            seeders = details.seeders ?: draft.fallbackSeeders,
            peers = details.peers ?: draft.fallbackPeers,
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = details.uploadEpochMillis,
            category = details.category ?: draft.fallbackCategory,
            descriptionPageUrl = draft.detailsPageUrl,
            magnetUri = details.magnetUri,
            fileDownloadLink = details.fileDownloadLink,
        )
    }
}
