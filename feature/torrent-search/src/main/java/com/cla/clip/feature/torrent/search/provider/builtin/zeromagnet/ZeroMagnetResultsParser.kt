package com.cla.clip.feature.torrent.search.provider.builtin.zeromagnet

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** 0Magnet 经请求前校验的详情草稿。 */
internal data class ZeroMagnetDraft(
    /** 已验证同站详情地址。 */
    val detailsPageUrl: String,
)
/** 0Magnet 独立列表 parser 与结果映射器。 */
internal class ZeroMagnetResultsParser(
    /** Provider 元数据。 */
    private val descriptor: ProviderDescriptor,
    /** URL 策略。 */
    private val urlPolicy: ZeroMagnetUrlPolicy,
    /** 计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 只产出合法详情草稿。 */
    suspend fun parse(
        /** 有上限 HTML。 */
        html: String,
        /** 已验证列表地址。 */
        pageUrl: String,
    ): List<ZeroMagnetDraft> = withContext(parsingDispatcher) {
        /** document 仅静态解析。 */
        val document = Jsoup.parse(html, pageUrl)
        document.select("table.file-list > tbody > tr").mapNotNullCancellable {
            /** row 是当前列表行。 */
            row -> parseRow(row, pageUrl)
        }
    }
    /** 修复固定上源遗漏：列表结果必须保留详情合法 magnet。 */
    suspend fun merge(
        /** 原始草稿。 */
        draft: ZeroMagnetDraft,
        /** 已解析详情。 */
        details: TorrentDetails,
    ): Torrent = withContext(parsingDispatcher) {
        Torrent(
            infoHash = details.infoHash, name = details.name, size = details.size,
            providerId = descriptor.id, providerName = descriptor.displayName,
            uploadEpochMillis = details.uploadEpochMillis, category = Category.PORN,
            descriptionPageUrl = draft.detailsPageUrl, magnetUri = details.magnetUri,
        )
    }
    /** 第一格详情链接必填。 */
    private fun parseRow(
        /** 当前列表行。 */
        row: Element,
        /** 页面基址。 */
        pageUrl: String,
    ): ZeroMagnetDraft? {
        /** candidate 是第一格链接。 */
        val candidate = row.selectFirst("td:nth-child(1) > a")?.attr("href")
        /** detailsUrl 必须在联网前通过同站校验。 */
        val detailsUrl = urlPolicy.optionalUrl(pageUrl, candidate) ?: return null
        return ZeroMagnetDraft(detailsUrl)
    }
}
