package com.cla.clip.feature.torrent.search.provider.builtin.torrentdatabase

import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** TorrentDatabase 列表页无网络 parser。 */
internal class TorrentDatabaseResultsParser(
    /** Provider 稳定身份来源。 */ private val descriptor: ProviderDescriptor,
    /** marker 和可选详情地址策略。 */ private val urlPolicy: TorrentDatabaseUrlPolicy,
    /** DOM、日期与领域映射调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 按固定表格行解析；无匹配容器是合法空结果。 */
    suspend fun parse(
        /** 有大小上限的 HTML。 */ html: String,
        /** 已验证页面地址。 */ pageUrl: String,
    ): List<Torrent> = withContext(parsingDispatcher) {
        /** rows 是固定 torrent-table 的直接数据行。 */ val rows = Jsoup.parse(html, pageUrl).select("table.torrent-table > tbody > tr")
        rows.mapNotNullCancellable { row -> parseItem(row, pageUrl) }
    }

    /** 第二 anchor 的非空名称与精确 marker/hash 必填，其余字段独立降级。 */
    private fun parseItem(
        /** 当前结果行。 */ row: Element,
        /** 已验证页面地址。 */ pageUrl: String,
    ): Torrent? {
        /** nameAnchor 同时承载标题和 marker。 */ val nameAnchor = row.selectFirst("td:nth-child(1) > a:nth-child(2)") ?: return null
        /** name 去除边界空白后必须非空。 */ val name = nameAnchor.ownText().trim().takeIf(String::isNotEmpty) ?: return null
        /** infoHash 只从精确同站 marker 路径离线读取。 */ val infoHash = urlPolicy.markerInfoHash(pageUrl, nameAnchor.attr("href")) ?: return null
        /** canonicalMagnet 先复用公共固定 tracker 快照。 */ val canonicalMagnet = MagnetInfoHashParser.create(infoHash)
        /** siteTrackerParameter 只编码一次站点 tracker，避免 %25 双编码。 */ val siteTrackerParameter = URLEncoder.encode(SITE_TRACKER, Charsets.UTF_8.name())
        return Torrent(
            infoHash = infoHash,
            name = name,
            size = row.selectFirst("td.size-cell")?.ownText()?.trim()?.takeIf(String::isNotEmpty),
            seeders = row.selectFirst("td:nth-child(5) > div > span:nth-child(1)")?.ownText()?.trim()?.toUIntOrNull(),
            peers = row.selectFirst("td:nth-child(5) > div > span:nth-child(2)")?.ownText()?.trim()?.toUIntOrNull(),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            /** date 是不会进入日志的列表日期文本。 */
            uploadEpochMillis = row.selectFirst("td.date-cell")?.ownText()?.let { date -> ProviderDateParser.parseUtcEpochMillis(date, "yyyy-MM-dd HH:mm:ss") },
            category = row.selectFirst("td:nth-child(2) > span.category-bubble")?.ownText()?.let(TorrentDatabaseCategoryMapper::fromPageText),
            descriptionPageUrl = urlPolicy.optionalDetailsUrl(pageUrl, row.selectFirst("td:nth-child(1) > a:nth-child(1)")?.attr("href")),
            magnetUri = "$canonicalMagnet&tr=$siteTrackerParameter",
        )
    }

    /** 固定站点 tracker 常量。 */ private companion object { /** 附加在公共 tracker 之后的站点 tracker。 */ private const val SITE_TRACKER = "https://developify.ca/announce" }
}
