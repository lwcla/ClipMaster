package com.cla.clip.feature.torrent.search.provider.builtin.uindex

import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** UIndex 列表页无网络 parser。 */
internal class UIndexResultsParser(
    /** Provider 稳定身份来源。 */ private val descriptor: ProviderDescriptor,
    /** 可选同站详情地址策略。 */ private val urlPolicy: UIndexUrlPolicy,
    /** 相对日期使用的可注入时钟。 */ private val currentTimeMillis: () -> Long,
    /** DOM、日期与领域映射调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 只读取文档顺序首个精确 sr/top 容器，并为整页固定一次当前时间。 */
    suspend fun parse(
        /** 有大小上限的 HTML。 */ html: String,
        /** 已验证页面地址。 */ pageUrl: String,
    ): List<Torrent> = withContext(parsingDispatcher) {
        /** document 静态解析 HTML 且不执行脚本。 */ val document = Jsoup.parse(html, pageUrl)
        /** container 是文档顺序首个真实 sr-table 或 top-table。 */ val container = document.selectFirst("table.sr-table, table.top-table") ?: return@withContext emptyList()
        /** currentEpochMillis 对同一响应只读取一次，保证各行相对日期一致。 */ val currentEpochMillis = currentTimeMillis()
        /** rows 只取容器 tbody 的直接行。 */ val rows = container.select("tbody > tr")
        rows.mapNotNullCancellable { row -> parseItem(row, pageUrl, currentEpochMillis) }
    }

    /** 名称与合法 magnet 必填，其余字段独立降级。 */
    private fun parseItem(
        /** 当前结果行。 */ row: Element,
        /** 已验证页面基址。 */ pageUrl: String,
        /** 本响应固定当前时间。 */ currentEpochMillis: Long,
    ): Torrent? {
        /** nameAnchor 同时承载名称和可选详情链接。 */ val nameAnchor = row.selectFirst("td.sr-col-name > a.sr-torrent-link") ?: return null
        /** name 去除边界空白后必须非空。 */ val name = nameAnchor.ownText().trim().takeIf(String::isNotEmpty) ?: return null
        /** magnetUri 来自固定 magnet anchor。 */ val magnetUri = row.selectFirst("td.sr-col-name > a.sr-magnet")?.attr("href") ?: return null
        /** infoHash 由公共解析器验证和规范化。 */ val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return null
        return Torrent(
            infoHash = infoHash,
            name = name,
            size = row.selectFirst("td.sr-col-size")?.ownText()?.trim()?.takeIf(String::isNotEmpty),
            seeders = row.selectFirst("td.sr-col-seeders > span.sr-seed")?.ownText()?.replace(",", "")?.trim()?.toUIntOrNull(),
            peers = row.selectFirst("td.sr-col-leechers > span.sr-leech")?.ownText()?.replace(",", "")?.trim()?.toUIntOrNull(),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            /** date 是不会进入日志的相对日期文本。 */
            uploadEpochMillis = row.selectFirst("td.sr-col-uploaded")?.ownText()?.let { date -> ProviderDateParser.parseRelativeEpochMillis(date, currentEpochMillis) },
            category = row.selectFirst("td.sr-col-cat > a.sr-cat-badge")?.ownText()?.let(UIndexCategoryMapper::fromPageText),
            descriptionPageUrl = urlPolicy.optionalSiteUrl(pageUrl, nameAnchor.attr("href")),
            magnetUri = magnetUri,
        )
    }
}
