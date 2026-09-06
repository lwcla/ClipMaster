package com.cla.clip.feature.torrent.search.provider.builtin.megapeer

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.model.contract.TorrentTracker
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.RussianProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** MegaPeer 列表页经必填校验后的无网络 draft。 */
internal data class MegaPeerDraft(
    /** 列表标题。 */
    val name: String,
    /** 列表大小。 */
    val size: String?,
    /** 做种数。 */
    val seeders: UInt?,
    /** 下载人数。 */
    val peers: UInt?,
    /** UTC 上传时间。 */
    val uploadEpochMillis: Long?,
    /** 已校验详情地址。 */
    val detailsPageUrl: String,
    /** 可选文件地址。 */
    val fileDownloadLink: String?,
)

/** MegaPeer 列表 DOM parser 与领域合并器，不持有网络。 */
internal class MegaPeerResultsParser(
    /** Provider 元数据。 */
    private val descriptor: ProviderDescriptor,
    /** URL 安全策略。 */
    private val urlPolicy: MegaPeerUrlPolicy,
    /** DOM 与领域映射计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 将固定列表行解析为安全 draft。 */
    suspend fun parse(
        /** 有大小上限 HTML。 */
        html: String,
        /** 已验证页面 URL。 */
        pageUrl: String,
    ): List<MegaPeerDraft> = withContext(parsingDispatcher) {
        /** document 只静态解析 HTML。 */
        val document = Jsoup.parse(html, pageUrl)
        document.select("div#index > table > tbody > tr.table_fon")
            .mapNotNullCancellable {
                /** row 是当前待解析列表行。 */ row -> parseRow(row, pageUrl)
            }
    }

    /** 在计算调度器把详情 hash 合并为列表结果。 */
    suspend fun merge(
        /** 原列表 draft。 */
        draft: MegaPeerDraft,
        /** 详情页规范化 hash。 */
        infoHash: String,
    ): Torrent = withContext(parsingDispatcher) {
        Torrent(
            infoHash = infoHash,
            name = draft.name,
            size = draft.size,
            seeders = draft.seeders,
            peers = draft.peers,
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = draft.uploadEpochMillis,
            descriptionPageUrl = draft.detailsPageUrl,
            fileDownloadLink = draft.fileDownloadLink,
        )
    }

    /** 名称和安全详情地址必填，其他字段独立降级。 */
    private fun parseRow(
        /** 当前列表行。 */
        row: Element,
        /** 页面基址。 */
        pageUrl: String,
    ): MegaPeerDraft? {
        /** nameAnchor 同时承载标题和详情地址。 */
        val nameAnchor = row.selectFirst("td:nth-child(2) > a:nth-child(2)")
            ?: return null
        /** name 必须非空。 */
        val name = nameAnchor.ownText().trim().takeIf(String::isNotEmpty)
            ?: return null
        /** detailsUrl 在启动详情请求前完成精确校验。 */
        val detailsUrl = urlPolicy.optionalUrl(pageUrl, nameAnchor.attr("href"))
            ?: return null
        return MegaPeerDraft(
            name = name,
            size = row.selectFirst("td:nth-child(3)")?.ownText()?.trim()
                ?.takeIf(String::isNotEmpty),
            seeders = row.selectFirst("td:nth-child(4) > font:nth-child(2)")
                ?.ownText()?.trim()?.toUIntOrNull(),
            peers = row.selectFirst("td:nth-child(4) > font:nth-child(4)")
                ?.ownText()?.trim()?.toUIntOrNull(),
            uploadEpochMillis = row.selectFirst("td:nth-child(1)")?.ownText()
                ?.let(RussianProviderDateParser::parseShortYearEpochMillis),
            detailsPageUrl = detailsUrl,
            fileDownloadLink = urlPolicy.optionalUrl(
                pageUrl,
                row.selectFirst("td:nth-child(2) > a:nth-child(1)")?.attr("href"),
            ),
        )
    }
}

/** MegaPeer 详情 DOM parser，不持有网络。 */
internal class MegaPeerDetailsParser(
    /** Provider 元数据。 */
    private val descriptor: ProviderDescriptor,
    /** URL 安全策略。 */
    private val urlPolicy: MegaPeerUrlPolicy,
    /** DOM 与领域映射计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 解析详情必填 name/magnet/hash，并独立降级可选 URL。 */
    suspend fun parse(
        /** 有大小上限 HTML。 */
        html: String,
        /** 已验证页面 URL。 */
        pageUrl: String,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** document 只静态解析且不执行脚本。 */
        val document = Jsoup.parse(html, pageUrl)
        /** name 是详情必填字段。 */
        val name = document.selectFirst("h1")?.text()?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return@withContext null
        /** magnetUri 必须包含合法 btih。 */
        val magnetUri = document.selectFirst("a[href^=\"magnet:?xt=\"]")
            ?.attr("href")
            ?: return@withContext null
        /** infoHash 使用公共稳定解析。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri)
            ?: return@withContext null
        /** descriptionElement 在提取媒体后原位清理。 */
        val descriptionElement = document.selectFirst(
            "table#details > tbody > tr:nth-child(1) > td:nth-child(2)",
        )
        /** posterUrl 只接受同站 HTTPS 候选。 */
        val posterUrl = urlPolicy.optionalUrl(
            pageUrl,
            descriptionElement?.selectFirst("img")?.attr("src"),
        )
        /** screenshotUrls 过滤非法候选并保持首次出现顺序。 */
        val screenshotUrls = descriptionElement
            ?.selectFirst("div.sp-head:containsOwn(Скриншоты)")
            ?.nextElementSibling()
            ?.select("img.lazy-screenshot")
            /** image 是当前截图候选。 */
            ?.mapNotNull { image -> urlPolicy.optionalUrl(pageUrl, image.attr("data-src")) }
            ?.distinct()
            .orEmpty()
        descriptionElement?.selectFirst("img")?.remove()
        descriptionElement?.select("div.sp-wrap")?.remove()
        /** categoryId 从固定类别链接末段读取。 */
        val categoryId = document.selectFirst("td:containsOwn(Категория)")
            ?.nextElementSibling()
            ?.selectFirst("a")
            ?.attr("href")
            ?.substringAfterLast('/')
        TorrentDetails(
            infoHash = infoHash,
            name = name,
            size = tableValue(document, "Размер")?.substringBefore('(')?.trim()
                ?.takeIf(String::isNotEmpty),
            seeders = tableValue(document, "Раздают")?.toUIntOrNull(),
            peers = tableValue(document, "Качают")?.toUIntOrNull(),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            category = mapCategory(categoryId),
            magnetUri = magnetUri,
            fileDownloadLink = urlPolicy.optionalUrl(
                pageUrl,
                document.selectFirst("a[href^=/download/]")?.attr("href"),
            ),
            description = descriptionElement?.html(),
            posterUrl = posterUrl,
            screenshotUrls = screenshotUrls,
            trackers = parseTrackers(magnetUri),
        )
    }

    /** 读取俄文标签相邻单元格文本。 */
    private fun tableValue(
        /** 页面文档。 */
        document: Document,
        /** 固定标签。 */
        label: String,
    ): String? = document.selectFirst("td:containsOwn($label)")
        ?.nextElementSibling()
        ?.ownText()
        ?.trim()

    /** 映射固定类别 ID。 */
    private fun mapCategory(
        /** 页面类别 ID。 */
        id: String?,
    ): Category = when (id) {
        "80", "79" -> Category.MOVIES
        "5", "6", "55", "21" -> Category.SERIES
        "94" -> Category.MUSIC
        "28" -> Category.GAMES
        "29" -> Category.APPS
        "52" -> Category.BOOKS
        else -> Category.OTHER
    }

    /** 从 magnet 提取可解析 tracker，坏候选只跳过。 */
    private fun parseTrackers(
        /** 已验证 magnet。 */
        magnetUri: String,
    ): List<TorrentTracker> = magnetUri.substringAfter('?', "")
        .split('&')
        /** parameter 是当前 magnet 查询参数。 */
        .mapNotNull { parameter ->
            /** encodedTracker 是 tr 参数原值。 */
            val encodedTracker = parameter.substringAfter("tr=", "")
                /** value 是仅在 tr 参数中保留的编码地址。 */
                .takeIf { value -> parameter.startsWith("tr=") && value.isNotEmpty() }
                ?: return@mapNotNull null
            /** trackerAddress 是 URL 解码后的候选。 */
            val trackerAddress = runCatching {
                URLDecoder.decode(encodedTracker, Charsets.UTF_8.name())
            }.getOrNull() ?: return@mapNotNull null
            /** trackerUri 仅用于确认地址包含 scheme。 */
            val trackerUri = runCatching { URI(trackerAddress) }.getOrNull()
                ?: return@mapNotNull null
            trackerAddress.takeIf { trackerUri.scheme != null }?.let(::TorrentTracker)
        }
        .distinctBy(TorrentTracker::address)
}
