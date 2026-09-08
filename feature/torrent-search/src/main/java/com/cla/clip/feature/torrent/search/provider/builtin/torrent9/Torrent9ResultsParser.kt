package com.cla.clip.feature.torrent.search.provider.builtin.torrent9

import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Torrent9 经校验的详情草稿。 */
internal data class Torrent9Draft(
    /** 同站详情地址。 */
    val detailsPageUrl: String,
)
/** Torrent9 独立列表 parser 与结果映射器。 */
internal class Torrent9ResultsParser(
    /** Provider 元数据。 */
    private val descriptor: ProviderDescriptor,
    /** URL 策略。 */
    private val urlPolicy: Torrent9UrlPolicy,
    /** 计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 列表只产出合法详情草稿。 */
    suspend fun parse(
        /** 有上限 HTML。 */
        html: String,
        /** 已验证页面地址。 */
        pageUrl: String,
    ): List<Torrent9Draft> = withContext(parsingDispatcher) {
        /** document 只静态解析 DOM。 */
        val document = Jsoup.parse(html, pageUrl)
        document.select("table > tbody > tr").mapNotNullCancellable {
            /** row 是当前列表行。 */
            row -> parseRow(row, pageUrl)
        }
    }
    /** 详情映射保留 magnet 与原顺序。 */
    suspend fun merge(
        /** 原始草稿。 */
        draft: Torrent9Draft,
        /** 完整详情。 */
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
    /** 第一格详情链接必填。 */
    private fun parseRow(
        /** 当前列表行。 */
        row: Element,
        /** 页面基址。 */
        pageUrl: String,
    ): Torrent9Draft? {
        /** candidate 是第一格链接候选。 */
        val candidate = row.selectFirst("td:nth-child(1) > a")?.attr("href")
        /** detailsUrl 在联网前严格校验。 */
        val detailsUrl = urlPolicy.optionalUrl(pageUrl, candidate) ?: return null
        return Torrent9Draft(detailsUrl)
    }
}
