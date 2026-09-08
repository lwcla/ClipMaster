package com.cla.clip.feature.torrent.search.provider.builtin.torrent9

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.FrenchTorrentCategoryMapper
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.normalizeProviderDisplaySize
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Torrent9 独立详情 DOM parser。 */
internal class Torrent9DetailsParser(
    /** Provider 元数据。 */
    private val descriptor: ProviderDescriptor,
    /** 可选媒体 URL 策略。 */
    private val urlPolicy: Torrent9UrlPolicy,
    /** 计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 名称与合法 magnet 必填。 */
    suspend fun parse(
        /** 有上限 HTML。 */
        html: String,
        /** 已验证详情地址。 */
        pageUrl: String,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** document 只静态解析。 */
        val document = Jsoup.parse(html, pageUrl)
        /** name 拒绝空标题。 */
        val name = document.selectFirst("div.movie-section h1")?.ownText()?.trim()?.takeIf(String::isNotEmpty) ?: return@withContext null
        /** magnetUri 从全页合法 scheme anchor 中读取。 */
        val magnetUri = document.selectFirst("a[href^=magnet:?]")?.attr("href") ?: return@withContext null
        /** infoHash 经公共 parser 严格规范化。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return@withContext null
        TorrentDetails(
            infoHash = infoHash, name = name, size = parseSize(valueAfterLabel(document, "Poids du torrent")),
            seeders = document.selectFirst("li[style=color:green]")?.ownText()?.trim()?.toUIntOrNull(),
            peers = document.selectFirst("li[style=color:red]")?.ownText()?.trim()?.toUIntOrNull(),
            providerId = descriptor.id, providerName = descriptor.displayName,
            uploadEpochMillis = valueAfterLabel(document, "Date d'ajout")?.ownText()?.trim()?.let {
                /** rawDate 是固定标签后的日期文本。 */
                rawDate -> ProviderDateParser.parseUtcEpochMillis(rawDate, "dd/MM/yyyy")
            },
            category = parseCategory(valueAfterLabel(document, "Catégories")),
            magnetUri = magnetUri, description = document.selectFirst("p.description_torrent")?.html(),
            posterUrl = urlPolicy.optionalUrl(pageUrl, document.selectFirst("div.movie-img > img")?.attr("src")),
        )
    }
    /** 按上源结构读取 strong.parent 后第二个元素 sibling。 */
    private fun valueAfterLabel(
        /** 详情文档。 */
        document: Document,
        /** 固定法语标签前缀。 */
        label: String,
    ): Element? {
        /** strong 是精确以标签开头的字段名。 */
        val strong = document.select("strong").firstOrNull {
            /** element 是待匹配标签节点。 */
            element -> element.ownText().trim().startsWith(label)
        } ?: return null
        return strong.parent()?.nextElementSibling()?.nextElementSibling()
    }
    /** 仅把合法数字加法语 o 单位转换为 B 单位。 */
    private fun parseSize(
        /** 标签后的候选元素。 */
        element: Element?,
    ): String? {
        /** rawSize 去除空白后再判断协议。 */
        val rawSize = element?.ownText()?.trim()?.takeIf(String::isNotEmpty) ?: return null
        /** match 接受数字及 Ko/Mo/Go/To，或已经规范的 KB/MB/GB/TB。 */
        val match = Regex("^(\\d+(?:[.,]\\d+)?)\\s*([KMGT]?)([oObB])$").matchEntire(rawSize) ?: return null
        /** number 将法语小数逗号统一为点。 */
        val number = match.groupValues[1].replace(',', '.')
        /** unit 保持容量量级并统一大写。 */
        val unit = match.groupValues[2].uppercase()
        return normalizeProviderDisplaySize(number + unit + "B")
    }
    /** 只接受固定 /torrents_{slug}.html 路径。 */
    private fun parseCategory(
        /** 类别值容器。 */
        element: Element?,
    ): Category {
        /** href 是类别 anchor 候选。 */
        val href = element?.selectFirst("a")?.attr("href").orEmpty()
        /** match 限定完整形状。 */
        val match = Regex("^/torrents_([^/?#]+)\\.html$").matchEntire(href) ?: return Category.OTHER
        return FrenchTorrentCategoryMapper.fromSlug(match.groupValues[1])
    }
}
