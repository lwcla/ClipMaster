package com.cla.clip.feature.torrent.search.provider.builtin.mikan

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import com.cla.clip.feature.torrent.search.provider.builtin.common.normalizeProviderDisplaySize
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Mikan 列表 DOM parser，不持有网络。 */
internal class MikanResultsParser(
    /** Provider 元数据。 */
    private val descriptor: ProviderDescriptor,
    /** URL 安全策略。 */
    private val urlPolicy: MikanUrlPolicy,
    /** DOM 与领域映射计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 解析固定列表行，无匹配即合法空结果。 */
    suspend fun parse(
        /** 有大小上限 HTML。 */
        html: String,
        /** 已验证页面 URL。 */
        pageUrl: String,
    ): List<Torrent> = withContext(parsingDispatcher) {
        /** document 只静态解析 HTML。 */
        val document = Jsoup.parse(html, pageUrl)
        document.select("tr.js-search-results-row")
            .mapNotNullCancellable {
                /** row 是当前待解析列表行。 */ row -> parseRow(row, pageUrl)
            }
    }

    /** 名称和合法 magnet 必填，可选字段独立降级。 */
    private fun parseRow(
        /** 当前结果行。 */
        row: Element,
        /** 页面基址。 */
        pageUrl: String,
    ): Torrent? {
        /** nameAnchor 同时承载标题和详情地址。 */
        val nameAnchor = row.selectFirst("td:nth-child(2) > a:nth-child(1)")
            ?: return null
        /** name 禁止空白。 */
        val name = nameAnchor.ownText().trim().takeIf(String::isNotEmpty)
            ?: return null
        /** magnetUri 来自固定 clipboard 属性。 */
        val magnetUri = row.selectFirst("td:nth-child(2) > a[data-clipboard-text]")
            ?.attr("data-clipboard-text")
            ?: return null
        /** infoHash 必须通过公共 magnet 解析。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri)
            ?: return null
        return Torrent(
            infoHash = infoHash,
            name = name,
            size = row.selectFirst("td:nth-child(3)")?.ownText()
                ?.let(::normalizeProviderDisplaySize),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = row.selectFirst("td:nth-child(4)")?.ownText()
                /** rawDate 是列表严格日期原文，仅在内存解析。 */
                ?.let { rawDate ->
                    ProviderDateParser.parseUtcEpochMillis(rawDate, "yyyy/MM/dd HH:mm")
                },
            category = Category.ANIME,
            descriptionPageUrl = urlPolicy.optionalUrl(pageUrl, nameAnchor.attr("href")),
            magnetUri = magnetUri,
            fileDownloadLink = urlPolicy.optionalDownloadUrl(
                pageUrl,
                row.selectFirst("td:nth-child(5) > a")?.attr("href"),
            ),
        )
    }
}

/** Mikan 详情 DOM parser，不持有网络。 */
internal class MikanDetailsParser(
    /** Provider 元数据。 */
    private val descriptor: ProviderDescriptor,
    /** URL 安全策略。 */
    private val urlPolicy: MikanUrlPolicy,
    /** DOM 与领域映射计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 解析标题、magnet 和中文标签。 */
    suspend fun parse(
        /** 有大小上限 HTML。 */
        html: String,
        /** 已验证页面 URL。 */
        pageUrl: String,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** document 只静态解析且不执行脚本。 */
        val document = Jsoup.parse(html, pageUrl)
        /** name 为详情必填标题。 */
        val name = document.selectFirst("p.episode-title")?.ownText()?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return@withContext null
        /** magnetUri 为详情必填链接。 */
        val magnetUri = document.selectFirst("a[href^=\"magnet:?\"]")?.attr("href")
            ?: return@withContext null
        /** infoHash 使用公共解析器。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri)
            ?: return@withContext null
        /** infos 是固定中文信息行。 */
        val infos = document.select("p.bangumi-info").map(Element::ownText)
        /** rawSize 去前缀后完整裁剪。 */
        val rawSize = infos.firstOrNull { text ->
            text.trimStart().startsWith(SIZE_PREFIX)
        }?.trim()?.removePrefix(SIZE_PREFIX)?.trim()
        /** rawDate 去前缀后完整裁剪。 */
        val rawDate = infos.firstOrNull { text ->
            text.trimStart().startsWith(DATE_PREFIX)
        }?.trim()?.removePrefix(DATE_PREFIX)?.trim()
        TorrentDetails(
            infoHash = infoHash,
            name = name,
            size = rawSize?.let(::normalizeProviderDisplaySize),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            /** date 是已去中文前缀的严格日期。 */
            uploadEpochMillis = rawDate?.let { date ->
                ProviderDateParser.parseUtcEpochMillis(date, "yyyy/MM/dd HH:mm")
            },
            category = Category.ANIME,
            magnetUri = magnetUri,
            fileDownloadLink = urlPolicy.optionalDownloadUrl(
                pageUrl,
                document.selectFirst("a[href^=/Download/]")?.attr("href"),
            ),
            description = document.selectFirst("div.episode-desc")?.html(),
        )
    }

    /** 固定中文标签。 */
    private companion object {
        /** 大小字段前缀。 */
        private const val SIZE_PREFIX = "文件大小："
        /** 日期字段前缀。 */
        private const val DATE_PREFIX = "发布日期："
    }
}
