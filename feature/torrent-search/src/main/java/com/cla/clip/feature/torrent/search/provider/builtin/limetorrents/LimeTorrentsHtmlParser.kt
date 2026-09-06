package com.cla.clip.feature.torrent.search.provider.builtin.limetorrents

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

/** LimeTorrents 列表页无网络 parser。 */
internal class LimeTorrentsResultsParser(
    /** Provider 稳定身份来源。 */ private val descriptor: ProviderDescriptor,
    /** 详情与 marker URL 策略。 */ private val urlPolicy: LimeTorrentsUrlPolicy,
    /** 相对日期基准时钟。 */ private val currentTimeMillis: () -> Long,
    /** DOM 与领域映射计算调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 按固定 table2 直接子行解析；无匹配即合法空结果。 */
    suspend fun parse(
        /** 有大小上限的 HTML。 */ html: String,
        /** 已验证页面 URL，用于解析相对详情。 */ pageUrl: String,
        /** latest/top 的已知分类回退。 */ searchCategory: Category?,
    ): List<Torrent> = withContext(parsingDispatcher) {
        /** document 静态解析 HTML 且不执行脚本。 */ val document = Jsoup.parse(html, pageUrl)
        /** row 是单个固定上源列表行。 */ document.select(LIST_ITEM).mapNotNullCancellable { row -> parseItem(row, pageUrl, searchCategory) }
    }

    /** 名称和安全 marker/hash 必填，可选字段独立降级。 */
    private fun parseItem(
        /** 当前结果行。 */ row: Element,
        /** 当前页面基址。 */ pageUrl: String,
        /** 调用方已知分类。 */ searchCategory: Category?,
    ): Torrent? {
        /** nameAnchor 同时承载标题与详情 href。 */ val nameAnchor = row.selectFirst(TORRENT_NAME) ?: return null
        /** name 必须非空。 */ val name = nameAnchor.ownText().takeIf(String::isNotBlank) ?: return null
        /** infoHash 只从精确 iTorrents marker 离线提取。 */ val infoHash = urlPolicy.markerInfoHash(row.selectFirst(FILE_DOWNLOAD_LINK)?.attr("href")) ?: return null
        /** dateAndCategory 安全拆分缺分隔符文本。 */ val dateAndCategory = splitDateAndCategory(row.selectFirst(UPLOAD_DATE_AND_CATEGORY)?.ownText())
        /** pageCategory 优先使用页面值，缺失时回退调用分类。 */ val pageCategory = dateAndCategory.second?.let(::mapCategory) ?: searchCategory
        return Torrent(
            infoHash = infoHash, name = name, size = row.selectFirst(SIZE)?.ownText()?.trim()?.takeIf(String::isNotBlank),
            seeders = row.selectFirst(SEEDERS)?.ownText()?.trim()?.toUIntOrNull(), peers = row.selectFirst(PEERS)?.ownText()?.trim()?.toUIntOrNull(),
            providerId = descriptor.id, providerName = descriptor.displayName,
            /** rawDate 是列表页相对日期，不进入日志。 */
            uploadEpochMillis = dateAndCategory.first?.let { rawDate -> ProviderDateParser.parseRelativeEpochMillis(rawDate, currentTimeMillis()) },
            category = pageCategory, descriptionPageUrl = urlPolicy.optionalDetailsUrl(pageUrl, nameAnchor.attr("href")),
            fileDownloadLink = urlPolicy.canonicalDownloadUrl(infoHash),
        )
    }

    /** 固定上源列表 selector。 */
    private companion object {
        /** 只读取 table2 的直接数据行。 */ private const val LIST_ITEM = ".table2 > tbody > tr"
        /** 第二个标题 anchor。 */ private const val TORRENT_NAME = "td:nth-child(1) > div.tt-name > a:nth-child(2)"
        /** 展示大小列。 */ private const val SIZE = "td:nth-child(3)"
        /** 做种列。 */ private const val SEEDERS = "td.tdseed"
        /** 下载人数列。 */ private const val PEERS = "td.tdleech"
        /** 日期与分类合并列。 */ private const val UPLOAD_DATE_AND_CATEGORY = "td:nth-child(2)"
        /** 第一个 iTorrents marker anchor。 */ private const val FILE_DOWNLOAD_LINK = "td:nth-child(1) > div.tt-name > a:nth-child(1)"
    }
}

/** LimeTorrents 详情页无网络 parser。 */
internal class LimeTorrentsDetailsParser(
    /** Provider 稳定身份来源。 */ private val descriptor: ProviderDescriptor,
    /** 下载 marker 安全策略。 */ private val urlPolicy: LimeTorrentsUrlPolicy,
    /** 相对日期基准时钟。 */ private val currentTimeMillis: () -> Long,
    /** DOM 与领域映射计算调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 名称、hash、magnet 必填且 hash 必须一致；可选 marker 非法只降级。 */
    suspend fun parse(/** 有大小上限的 HTML。 */ html: String): TorrentDetails? = withContext(parsingDispatcher) {
        /** document 静态解析且不执行脚本。 */ val document = Jsoup.parse(html)
        /** infoHash 来自固定详情表格。 */ val infoHash = document.selectFirst(INFO_HASH)?.ownText()?.let(MagnetInfoHashParser::normalize) ?: return@withContext null
        /** magnetUri 必须合法且与表格 hash 一致。 */ val magnetUri = document.selectFirst(MAGNET_URI)?.attr("href") ?: return@withContext null
        if (MagnetInfoHashParser.parse(magnetUri) != infoHash) return@withContext null
        /** name 是详情必填展示字段。 */ val name = document.selectFirst(NAME)?.ownText()?.takeIf(String::isNotBlank) ?: return@withContext null
        /** dateAndCategory 使用独立空白分隔符，不能误拆 minutes。 */ val dateAndCategory = splitDateAndCategory(document.selectFirst(UPLOAD_DATE_AND_CATEGORY)?.text())
        /** optionalHash 只从合法 marker 提取。 */ val optionalHash = urlPolicy.markerInfoHash(document.selectFirst(FILE_DOWNLOAD_LINK)?.attr("href"))
        /** fileDownloadLink 仅在 marker 与详情身份相同才保留。 */ val fileDownloadLink = optionalHash?.takeIf { hash -> hash == infoHash }?.let(urlPolicy::canonicalDownloadUrl)
        TorrentDetails(
            infoHash = infoHash, name = name, size = document.selectFirst(SIZE)?.ownText()?.trim()?.takeIf(String::isNotBlank),
            seeders = document.selectFirst(SEEDERS)?.ownText()?.removePrefix("Seeders : ")?.trim()?.toUIntOrNull(),
            peers = document.selectFirst(PEERS)?.ownText()?.removePrefix("Leechers : ")?.trim()?.toUIntOrNull(),
            providerId = descriptor.id, providerName = descriptor.displayName,
            /** rawDate 是详情页相对日期，不进入日志。 */
            uploadEpochMillis = dateAndCategory.first?.let { rawDate -> ProviderDateParser.parseRelativeEpochMillis(rawDate, currentTimeMillis()) },
            category = dateAndCategory.second?.let(::mapCategory), magnetUri = magnetUri, fileDownloadLink = fileDownloadLink,
        )
    }

    /** 固定详情页 selector。 */
    private companion object {
        /** 第一行 hash 值。 */ private const val INFO_HASH = "#content > div:nth-child(6) > div:nth-child(1) > div > table > tbody > tr:nth-child(1) > td:nth-child(2)"
        /** 详情标题。 */ private const val NAME = "#content > h1"
        /** 第三行大小值。 */ private const val SIZE = "#content > div:nth-child(6) > div:nth-child(1) > div > table > tbody > tr:nth-child(3) > td:nth-child(2)"
        /** 做种展示。 */ private const val SEEDERS = "#content > span.greenish"
        /** 下载人数展示。 */ private const val PEERS = "#content > span.reddish"
        /** 第二行日期分类。 */ private const val UPLOAD_DATE_AND_CATEGORY = "#content > div:nth-child(6) > div:nth-child(1) > div > table > tbody > tr:nth-child(2) > td:nth-child(2)"
        /** 任意合法 magnet anchor。 */ private const val MAGNET_URI = "a[href^=\"magnet:?\"]"
        /** 固定详情下载按钮。 */ private const val FILE_DOWNLOAD_LINK = "#content > div:nth-child(6) > div:nth-child(1) > div > div:nth-child(7) > div > a"
    }
}

/** 安全拆分列表的 `- in` 与详情的空白 `in`，缺分类时保留完整日期。 */
private fun splitDateAndCategory(/** 日期和可选分类合并文本。 */ rawText: String?): Pair<String?, String?> {
    /** text 去除边界空白，空值返回两个 null。 */ val text = rawText?.trim()?.takeIf(String::isNotEmpty) ?: return null to null
    /** listDelimiter 优先匹配列表页固定分隔符。 */ val listDelimiter = " - in "
    /** detailDelimiter 仅匹配完整空白单词，不命中 minutes 内部。 */ val detailDelimiter = " in "
    /** delimiterIndex 是选中分隔符的位置。 */ val delimiterIndex = text.indexOf(listDelimiter).takeIf { index -> index >= 0 }
        ?: text.lastIndexOf(detailDelimiter).takeIf { index -> index >= 0 }
    if (delimiterIndex == null) return text to null
    /** delimiterLength 与实际命中的列表或详情分隔符一致。 */ val delimiterLength = if (text.startsWith(listDelimiter, delimiterIndex)) listDelimiter.length else detailDelimiter.length
    /** rawDate 保留相对日期主体。 */ val rawDate = text.substring(0, delimiterIndex).trim().takeIf(String::isNotEmpty)
    /** rawCategory 去除尾部句点后交给 mapper。 */ val rawCategory = text.substring(delimiterIndex + delimiterLength).trim().removeSuffix(".").trim().takeIf(String::isNotEmpty)
    return rawDate to rawCategory
}

/** LimeTorrents 固定页面分类文本映射，输入先 trim，未知值回退 OTHER。 */
private fun mapCategory(/** 页面分类文本。 */ rawCategory: String): Category = when (rawCategory.trim()) {
    "TV" -> Category.SERIES; "Movie" -> Category.MOVIES; "Music" -> Category.MUSIC; "App" -> Category.APPS
    "E-book" -> Category.BOOKS; "Anime" -> Category.ANIME; "Games" -> Category.GAMES; else -> Category.OTHER
}
