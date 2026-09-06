package com.cla.clip.feature.torrent.search.provider.builtin.xxxtracker

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.RussianProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** XXXTracker 列表页独立 DOM parser，不与 Rutor 混用 anchor 顺序。 */
internal class XxxTrackerResultsParser(
    /** Provider 稳定身份来源。 */
    private val descriptor: ProviderDescriptor,
    /** 同站选填 URL 策略。 */
    private val urlPolicy: XxxTrackerUrlPolicy,
    /** DOM 与领域映射计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 丢弃固定首行后按原顺序解析合法条目。 */
    suspend fun parse(
        /** 有共享大小上限的 HTML。 */
        html: String,
        /** 已审核列表页 URL。 */
        pageUrl: String,
    ): List<Torrent> = withContext(parsingDispatcher) {
        /** document 只静态解析 HTML，不执行脚本。 */
        val document = Jsoup.parse(html, pageUrl)
        document.select(LIST_ITEMS)
            .drop(1)
            /** row 是当前固定列表行。 */
            .mapNotNullCancellable { row -> parseRow(row, pageUrl) }
    }

    /** 名称与合法 magnet/hash 必填，其他字段独立降级。 */
    private fun parseRow(
        /** 当前列表行。 */
        row: Element,
        /** 当前列表页基址。 */
        pageUrl: String,
    ): Torrent? {
        /** nameAnchor 固定为第二格第三个 anchor。 */
        val nameAnchor = row.selectFirst(NAME_ANCHOR) ?: return null
        /** name 必须包含非空展示文本。 */
        val name = nameAnchor.ownText().trim().takeIf(String::isNotEmpty) ?: return null
        /** magnetUri 固定为第二格第一个 anchor，与 Rutor 顺序不同。 */
        val magnetUri = row.selectFirst(MAGNET_ANCHOR)?.attr("href") ?: return null
        /** infoHash 通过公共规则规范化并校验。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return null
        return Torrent(
            infoHash = infoHash,
            name = name,
            size = row.selectFirst(SIZE_CELL)?.ownText()?.trim()?.takeIf(String::isNotEmpty),
            seeders = row.selectFirst(SEEDERS_CELL)?.ownText()?.trim()?.toUIntOrNull(),
            peers = row.selectFirst(PEERS_CELL)?.ownText()?.trim()?.toUIntOrNull(),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = row.selectFirst(DATE_CELL)?.ownText()
                ?.let(RussianProviderDateParser::parseShortYearEpochMillis),
            category = Category.PORN,
            descriptionPageUrl = urlPolicy.optionalUrl(pageUrl, nameAnchor.attr("href")),
            magnetUri = magnetUri,
            fileDownloadLink = urlPolicy.optionalUrl(
                pageUrl,
                row.selectFirst(FILE_ANCHOR)?.attr("href"),
            ),
        )
    }

    /** XXXTracker 固定列表 selector。 */
    private companion object {
        /** 表头和结果共同选择，调用方显式丢弃第一行。 */
        private const val LIST_ITEMS = "table > tbody > tr"
        /** 第二格第三个 anchor 同时承载名称与详情地址。 */
        private const val NAME_ANCHOR = "td:nth-child(2) > a:nth-child(3)"
        /** 第二格第一个 anchor 承载 magnet。 */
        private const val MAGNET_ANCHOR = "td:nth-child(2) > a:nth-child(1)"
        /** 第二格第二个 anchor 承载 torrent 文件地址。 */
        private const val FILE_ANCHOR = "td:nth-child(2) > a:nth-child(2)"
        /** 第三格为展示大小。 */
        private const val SIZE_CELL = "td:nth-child(3)"
        /** 第四格第一个 span 为做种数。 */
        private const val SEEDERS_CELL = "td:nth-child(4) > span:nth-child(1)"
        /** 第四格第二个 span 为下载人数。 */
        private const val PEERS_CELL = "td:nth-child(4) > span:nth-child(2)"
        /** 第一格为俄文短日期。 */
        private const val DATE_CELL = "td:nth-child(1)"
    }
}
