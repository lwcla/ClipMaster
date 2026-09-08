package com.cla.clip.feature.torrent.search.provider.builtin.oxtorrent

import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** OxTorrent 列表中通过请求前校验的详情草稿。 */
internal data class OxTorrentDraft(
    /** 已验证的同站详情地址。 */
    val detailsPageUrl: String,
)

/** OxTorrent 独立列表 DOM parser 与结果映射器。 */
internal class OxTorrentResultsParser(
    /** Provider 稳定元数据。 */
    private val descriptor: ProviderDescriptor,
    /** 同站 URL 策略。 */
    private val urlPolicy: OxTorrentUrlPolicy,
    /** DOM 解析计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 只解析合法详情草稿，不执行网络。 */
    suspend fun parse(
        /** 有上限的列表 HTML。 */
        html: String,
        /** 已验证页面地址。 */
        pageUrl: String,
    ): List<OxTorrentDraft> = withContext(parsingDispatcher) {
        /** document 仅作静态 DOM 解析。 */
        val document = Jsoup.parse(html, pageUrl)
        document.select("table > tbody > tr").mapNotNullCancellable {
            /** row 是当前列表行。 */
            row -> parseRow(row, pageUrl)
        }
    }
    /** 将完整详情映射为列表结果并保留合法 magnet。 */
    suspend fun merge(
        /** 原列表草稿。 */
        draft: OxTorrentDraft,
        /** 已解析详情。 */
        details: TorrentDetails,
    ): Torrent = withContext(parsingDispatcher) {
        Torrent(
            infoHash = details.infoHash, name = details.name, size = details.size,
            seeders = details.seeders, peers = details.peers, providerId = descriptor.id,
            providerName = descriptor.displayName, uploadEpochMillis = details.uploadEpochMillis,
            category = details.category, descriptionPageUrl = draft.detailsPageUrl,
            magnetUri = details.magnetUri,
        )
    }
    /** 第一格详情链接必填且必须同站。 */
    private fun parseRow(
        /** 当前列表行。 */
        row: Element,
        /** 当前页面基址。 */
        pageUrl: String,
    ): OxTorrentDraft? {
        /** candidate 是第一格 anchor 的链接候选。 */
        val candidate = row.selectFirst("td:nth-child(1) > a")?.attr("href")
        /** detailsUrl 在任何详情请求前完成校验。 */
        val detailsUrl = urlPolicy.optionalUrl(pageUrl, candidate) ?: return null
        return OxTorrentDraft(detailsUrl)
    }
}
