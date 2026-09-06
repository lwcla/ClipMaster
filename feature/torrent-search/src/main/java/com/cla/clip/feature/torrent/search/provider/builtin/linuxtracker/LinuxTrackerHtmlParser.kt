package com.cla.clip.feature.torrent.search.provider.builtin.linuxtracker

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** LinuxTracker 列表页无网络 parser。 */
internal class LinuxTrackerResultsParser(
    /** Provider 稳定身份来源。 */ private val descriptor: ProviderDescriptor,
    /** 可选同站 URL 策略。 */ private val urlPolicy: LinuxTrackerUrlPolicy,
    /** DOM 与领域映射计算调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 完整保留上游 table.lista 与带 title 详情 anchor 选择器，避免嵌套表重复。 */
    suspend fun parse(
        /** 有大小上限的 HTML。 */ html: String,
        /** 已验证页面 URL。 */ pageUrl: String,
    ): List<Torrent> = withContext(parsingDispatcher) {
        /** document 静态解析 HTML 且不执行脚本。 */ val document = Jsoup.parse(html, pageUrl)
        /** row 是包含指定详情 anchor 的外层结果行。 */ document.select(LIST_ITEM).mapNotNullCancellable { row -> parseItem(row, pageUrl) }
    }

    /** 名称与合法 magnet 必填；可选链接非法时独立降级。 */
    private fun parseItem(
        /** 当前外层结果行。 */ row: Element,
        /** 当前页面基址。 */ pageUrl: String,
    ): Torrent? {
        /** nameAnchor 必须同时带固定 href 前缀与 title。 */ val nameAnchor = row.selectFirst(TORRENT_NAME) ?: return null
        /** name 必须非空。 */ val name = nameAnchor.ownText().takeIf(String::isNotBlank) ?: return null
        /** magnetUri 是 torrent 身份来源。 */ val magnetUri = row.selectFirst(MAGNET_URI)?.attr("href") ?: return null
        /** infoHash 必须可规范化。 */ val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return null
        return Torrent(
            infoHash = infoHash, name = name, size = row.selectFirst(SIZE)?.ownText()?.trim()?.takeIf(String::isNotBlank),
            seeders = row.selectFirst(SEEDERS)?.ownText()?.trim()?.toUIntOrNull(), peers = row.selectFirst(PEERS)?.ownText()?.trim()?.toUIntOrNull(),
            providerId = descriptor.id, providerName = descriptor.displayName,
            /** rawDate 是列表页固定 UTC 日期。 */
            uploadEpochMillis = row.selectFirst(UPLOAD_DATE)?.ownText()?.trim()?.let { rawDate -> ProviderDateParser.parseUtcEpochMillis(rawDate, "dd/MM/yyyy") },
            category = Category.APPS, magnetUri = magnetUri,
            fileDownloadLink = urlPolicy.optionalUrl(pageUrl, row.selectFirst(FILE_DOWNLOAD_LINK)?.attr("href")),
            descriptionPageUrl = urlPolicy.optionalUrl(pageUrl, nameAnchor.attr("href")),
        )
    }

    /** 固定上源列表 selector。 */
    private companion object {
        /** 只选包含目标详情 anchor 的外层 100% table 行。 */ private const val LIST_ITEM = "table.lista[width=\"100%\"] > tbody > tr:has(a[href^=\"index.php?page=torrent-details&id=\"][title])"
        /** 名称详情 anchor。 */ private const val TORRENT_NAME = "a[href^=\"index.php?page=torrent-details&id=\"][title]"
        /** 嵌套信息表第二行大小。 */ private const val SIZE = "td:nth-child(2) > table > tbody > tr:nth-child(2) > td"
        /** 嵌套信息表第三行做种数。 */ private const val SEEDERS = "td:nth-child(2) > table > tbody > tr:nth-child(3) > td"
        /** 嵌套信息表第四行下载人数。 */ private const val PEERS = "td:nth-child(2) > table > tbody > tr:nth-child(4) > td"
        /** 嵌套信息表第一行日期。 */ private const val UPLOAD_DATE = "td:nth-child(2) > table > tbody > tr > td"
        /** 合法磁力 anchor。 */ private const val MAGNET_URI = "a[href^=\"magnet:?\"]"
        /** 同站 torrent 下载 anchor。 */ private const val FILE_DOWNLOAD_LINK = "a[href^=\"index.php?page=downloadcheck&id=\"]"
    }
}

/** LinuxTracker 详情页无网络 parser。 */
internal class LinuxTrackerDetailsParser(
    /** Provider 稳定身份来源。 */ private val descriptor: ProviderDescriptor,
    /** DOM 与领域映射计算调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 名称和合法 magnet 必填；字段缺失与人数格式错误只降级。 */
    suspend fun parse(
        /** 有大小上限的 HTML。 */ html: String,
        /** 已验证页面 URL，仅作为 Jsoup 基址。 */ pageUrl: String,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** document 静态解析 HTML 且不执行脚本。 */ val document = Jsoup.parse(html, pageUrl)
        /** name 来自 itemprop 稳定标记。 */ val name = document.selectFirst(TORRENT_NAME)?.ownText()?.takeIf(String::isNotBlank) ?: return@withContext null
        /** magnetUri 是详情必填身份。 */ val magnetUri = document.selectFirst(MAGNET_URI)?.attr("href") ?: return@withContext null
        /** infoHash 必须合法。 */ val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return@withContext null
        /** peerCounts 要求完整 seeds/leechers 两段，否则两个值都为空。 */ val peerCounts = parsePeerCounts(labelValue(document, "peers"))
        TorrentDetails(
            infoHash = infoHash, name = name, size = labelValue(document, "Size"), seeders = peerCounts.first, peers = peerCounts.second,
            providerId = descriptor.id, providerName = descriptor.displayName,
            /** rawDate 是详情页固定 UTC 日期。 */
            uploadEpochMillis = labelValue(document, "AddDate")?.let { rawDate -> ProviderDateParser.parseUtcEpochMillis(rawDate, "dd/MM/yyyy") },
            category = Category.APPS, uploader = labelValue(document, "Uploader"), magnetUri = magnetUri,
            description = document.selectFirst(DESCRIPTION)?.html(),
        )
    }

    /** 按标签单元格读取相邻值；空值降级为空。 */
    private fun labelValue(
        /** 已解析详情文档。 */ document: org.jsoup.nodes.Document,
        /** 固定上源标签。 */ label: String,
    ): String? = document.selectFirst("td:containsOwn($label)")?.nextElementSibling()?.ownText()?.trim()?.takeIf(String::isNotEmpty)

    /** 仅接受完整 `seeds: N, leechers: N`，缺分隔符或空字段统一降级。 */
    private fun parsePeerCounts(/** 合并人数文本。 */ rawStats: String?): Pair<UInt?, UInt?> {
        /** parts 必须恰好两个逗号分段。 */ val parts = rawStats?.split(',', limit = 2) ?: return null to null
        if (parts.size != 2) return null to null
        /** seeders 读取固定前缀后的无符号值；value 是去前缀候选。 */
        val seeders = parts[0].trim().removePrefix("seeds: ").takeIf { value -> value != parts[0].trim() }?.toUIntOrNull()
        /** peers 读取固定前缀后的无符号值；value 是去前缀候选。 */
        val peers = parts[1].trim().removePrefix("leechers: ").takeIf { value -> value != parts[1].trim() }?.toUIntOrNull()
        return if (seeders != null && peers != null) seeders to peers else null to null
    }

    /** 固定详情 selector。 */
    private companion object {
        /** 详情名称。 */ private const val TORRENT_NAME = "span[itemprop=\"name\"]"
        /** 不可信 HTML 描述。 */ private const val DESCRIPTION = "span[itemprop=\"blogPost\"]"
        /** 磁力 anchor。 */ private const val MAGNET_URI = "a[href^=\"magnet:?\"]"
    }
}
