package com.cla.clip.feature.torrent.search.provider.builtin.oxtorrent

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.FrenchTorrentCategoryMapper
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** OxTorrent 独立详情 DOM parser。 */
internal class OxTorrentDetailsParser(
    /** Provider 稳定元数据。 */
    private val descriptor: ProviderDescriptor,
    /** 媒体同站 URL 策略。 */
    private val urlPolicy: OxTorrentUrlPolicy,
    /** DOM 解析计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 名称和合法 magnet 必填，其他字段各自安全降级。 */
    suspend fun parse(
        /** 有上限的详情 HTML。 */
        html: String,
        /** 已验证详情地址。 */
        pageUrl: String,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** document 只静态解析。 */
        val document = Jsoup.parse(html, pageUrl)
        /** name 拒绝空标题。 */
        val name = document.selectFirst("div.title > a")?.ownText()?.trim()?.takeIf(String::isNotEmpty) ?: return@withContext null
        /** magnetUri 必须能提取合法 hash。 */
        val magnetUri = document.selectFirst("div.btn-magnet > a")?.attr("href") ?: return@withContext null
        /** infoHash 是公共 parser 规范化后的稳定身份。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return@withContext null
        TorrentDetails(
            infoHash = infoHash, name = name, size = labelValue(document, "Poids du fichier:"),
            seeders = labelValue(document, "Seeders:")?.toUIntOrNull(),
            peers = labelValue(document, "Leechers:")?.toUIntOrNull(),
            providerId = descriptor.id, providerName = descriptor.displayName,
            uploadEpochMillis = labelValue(document, "Date d'ajout:")?.let {
                /** rawDate 是固定法语标签后的日期。 */
                rawDate -> ProviderDateParser.parseUtcEpochMillis(rawDate, "dd/MM/yyyy")
            },
            category = parseCategory(document), magnetUri = magnetUri,
            description = document.selectFirst("#torrentsdesc")?.html(),
            posterUrl = urlPolicy.optionalUrl(pageUrl, document.selectFirst("img.img-rounded")?.attr("src")),
        )
    }
    /** 按法语 td 标签读取相邻值。 */
    private fun labelValue(
        /** 详情文档。 */
        document: Document,
        /** 固定标签。 */
        label: String,
    ): String? = document.select("td").firstOrNull {
        /** cell 是待匹配标签格。 */
        cell -> cell.ownText().trim() == label
    }?.nextElementSibling()?.text()?.trim()?.takeIf(String::isNotEmpty)
    /** 只接受精确 /torrents/{slug} 类别链接。 */
    private fun parseCategory(
        /** 详情文档。 */
        document: Document,
    ): Category {
        /** href 是法语类别相邻格中的链接。 */
        val href = document.select("td").firstOrNull {
            /** cell 是待定位类别标签格。 */
            cell -> cell.ownText().trim() == "Catégories:"
        }?.nextElementSibling()?.selectFirst("a")?.attr("href").orEmpty()
        /** match 限制类别路径完整形状。 */
        val match = Regex("^/torrents/([^/?#]+)$").matchEntire(href) ?: return Category.OTHER
        return FrenchTorrentCategoryMapper.fromSlug(match.groupValues[1])
    }
}
