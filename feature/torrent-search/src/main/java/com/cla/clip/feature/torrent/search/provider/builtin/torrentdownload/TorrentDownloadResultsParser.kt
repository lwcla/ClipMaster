package com.cla.clip.feature.torrent.search.provider.builtin.torrentdownload

import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** TorrentDownload 列表 DOM parser，不持有网络能力。 */
internal class TorrentDownloadResultsParser(
    /** 结果稳定 Provider 身份。 */ private val descriptor: ProviderDescriptor,
    /** 详情页精确主站策略。 */ private val siteUrlPolicy: BuiltinProviderUrlPolicy,
    /** 相对日期使用的注入时钟。 */ private val currentEpochMillis: () -> Long,
    /** DOM、日期与领域映射计算调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 解析列表行；明确的无结果标题和缺容器都返回空集合。 */
    suspend fun parse(
        /** 受统一大小限制的列表 HTML。 */ html: String,
        /** 已验证列表页基址。 */ pageUrl: String,
    ): List<Torrent> = withContext(parsingDispatcher) {
        /** document 仅静态解析，不执行页面脚本。 */
        val document = Jsoup.parse(html, pageUrl)
        if (document.selectFirst("h2")?.ownText() == "No Results Found") return@withContext emptyList()
        /** rows 是固定 wrapper 最后一张结果表中的数据行。 */
        val rows = document.select("div.wrapper > table.table2:last-of-type > tbody > tr")
        // row 是当前列表候选，协作遍历会在每项前检查父取消。
        rows.mapNotNullCancellable { row -> parseRow(row, pageUrl) }
    }

    /** 名称、同站详情 URL 与其倒数第二路径段合法 hash 必填。 */
    private fun parseRow(
        /** 当前结果行。 */ row: Element,
        /** 当前列表页 URL。 */ pageUrl: String,
    ): Torrent? {
        /** nameAnchor 同时承载展示名称和详情候选。 */
        val nameAnchor = row.selectFirst("td:nth-child(1) > div.tt-name > a") ?: return null
        /** name 去边界空白后必须非空。 */
        val name = nameAnchor.text().trim().takeIf(String::isNotEmpty) ?: return null
        /** detailsPageUrl 必须解析为精确同站 HTTPS。 */
        val detailsPageUrl = runCatching {
            siteUrlPolicy.resolveOptionalHttpsUrl(pageUrl, nameAnchor.attr("href"))
        }.getOrNull() ?: return null
        /** detailsUrl 只用于读取已经过安全校验的路径段。 */
        val detailsUrl = siteUrlPolicy.requireHttpsUrl(detailsPageUrl)
        /** rawInfoHash 保留固定上游的倒数第二路径段契约。 */
        val rawInfoHash = detailsUrl.pathSegments.dropLast(1).lastOrNull() ?: return null
        /** infoHash 必须规范化为稳定 40 位小写 hex。 */
        val infoHash = MagnetInfoHashParser.normalize(rawInfoHash) ?: return null
        return Torrent(
            infoHash = infoHash,
            name = name,
            size = row.selectFirst("td:nth-child(3)")?.ownText()?.trim()?.takeIf(String::isNotEmpty),
            seeders = row.selectFirst("td:nth-child(4)")?.ownText()?.replace(",", "")?.trim()?.toUIntOrNull(),
            peers = row.selectFirst("td:nth-child(5)")?.ownText()?.replace(",", "")?.trim()?.toUIntOrNull(),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = row.selectFirst("td:nth-child(2)")?.ownText()
                /** rawDate 是列表相对日期原文，仅在内存解析。 */
                ?.let { rawDate ->
                    ProviderDateParser.parseRelativeEpochMillis(rawDate, currentEpochMillis())
                },
            category = row.selectFirst("td:nth-child(1) > div.tt-name > span")?.ownText()
                ?.let(TorrentDownloadCategoryMapper::fromPageText),
            descriptionPageUrl = detailsPageUrl,
        )
    }
}
