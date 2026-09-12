package com.cla.clip.feature.torrent.search.torznab

import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.TorrentFileSizeFormatter
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/** 将 Torznab RSS/XML 响应逐项映射为统一 Torrent 领域模型。 */
internal object TorznabResponseParser {
    /** 解析完整响应；坏条目只跳过自身，顶层损坏返回空列表。 */
    fun parse(xml: String, config: TorznabConfig): List<Torrent> {
        /** document 使用 XML parser 保留命名空间标签且绝不执行脚本。 */
        val document = runCatching { Jsoup.parse(xml, "", Parser.xmlParser()) }.getOrNull()
            ?: return emptyList()
        return document.getElementsByTag("item")
            /** item 是当前独立容错的 RSS 条目。 */
            .mapNotNull { item -> parseItem(item, config) }
            /** torrent 以规范化 infoHash 去重，保留 feed 中首个有效条目。 */
            .distinctBy { torrent -> torrent.infoHash }
    }

    /** 解析单个 RSS item；名称或可验证身份缺失时返回 null。 */
    private fun parseItem(item: Element, config: TorznabConfig): Torrent? {
        /** name 是用户展示字段，空值不能形成有效结果。 */
        val name = item.firstText("title")?.takeIf(String::isNotEmpty) ?: return null
        /** attributes 按 name 建表，兼容带或不带 torznab 命名空间前缀的响应。 */
        val attributes = item.getAllElements()
            /** element 只保留 Torznab attr 元素。 */
            .filter { element -> element.tagName() == "attr" || element.tagName().endsWith(":attr") }
            /** element 用小写 name 作为稳定查找键，重复字段保留最后一个。 */
            .associate { element -> element.attr("name").lowercase() to element.attr("value") }
        /** rawMagnet 按 Torznab 属性、link、guid、enclosure 顺序寻找磁力候选。 */
        val rawMagnet = sequenceOf(
            attributes["magneturl"],
            item.firstText("link"),
            item.firstText("guid"),
            item.getElementsByTag("enclosure").firstOrNull()?.attr("url"),
            ).filterNotNull().firstOrNull { /** candidate 是待识别 magnet scheme 的候选链接。 */ candidate ->
                candidate.startsWith("magnet:", ignoreCase = true)
            }
        /** magnetHash 只接受可规范化 btih，恶意或损坏 magnet 不进入领域结果。 */
        val magnetHash = rawMagnet?.let(MagnetInfoHashParser::parse)
        /** attributeHash 兼容常见 infohash 与 info_hash 属性名。 */
        val attributeHash = sequenceOf(attributes["infohash"], attributes["info_hash"])
            .filterNotNull()
            .mapNotNull(MagnetInfoHashParser::normalize)
            .firstOrNull()
        /** infoHash 优先使用已验证 magnet 身份，缺失时使用裸 hash 属性。 */
        val infoHash = magnetHash ?: attributeHash ?: return null
        /** magnetUri 保留有效上游 magnet；只有裸 hash 时才生成统一 canonical magnet。 */
        val magnetUri = rawMagnet?.takeIf { magnetHash != null } ?: MagnetInfoHashParser.create(infoHash)
        /** sizeBytes 兼容标准 size 元素和 Torznab size 属性。 */
        val sizeBytes = (item.firstText("size") ?: attributes["size"])?.toLongOrNull()
        /** seeders 只接受 UInt 范围内的非负值。 */
        val seeders = attributes["seeders"]?.toUIntOrNull()
        /** peers 优先 peers 字段，缺失时兼容 leechers。 */
        val peers = (attributes["peers"] ?: attributes["leechers"])?.toUIntOrNull()
        /** uploadEpochMillis 严格解析 RSS RFC1123 日期，坏日期独立降级。 */
        val uploadEpochMillis = item.firstText("pubDate")?.let(ProviderDateParser::parseRfc1123EpochMillis)
        /** fileDownloadLink 只保留合法 HTTP/HTTPS 地址，不把 magnet 重复当作文件链接。 */
        val fileDownloadLink = sequenceOf(
            item.getElementsByTag("enclosure").firstOrNull()?.attr("url"),
            item.firstText("link"),
        ).filterNotNull().mapNotNull(::normalizeHttpUrl).firstOrNull()
        return Torrent(
            infoHash = infoHash,
            name = name,
            size = sizeBytes?.let(TorrentFileSizeFormatter::formatBytes),
            seeders = seeders,
            peers = peers,
            providerId = ProviderId(config.id.value),
            providerName = config.name,
            uploadEpochMillis = uploadEpochMillis,
            category = config.category,
            magnetUri = magnetUri,
            fileDownloadLink = fileDownloadLink,
        )
    }

    /** 读取单个直接子孙标签文本；缺失或空白返回 null。 */
    private fun Element.firstText(tagName: String): String? =
        getElementsByTag(tagName).firstOrNull()?.text()?.trim()?.takeIf(String::isNotEmpty)

    /** 规范化响应中的 HTTP/HTTPS 文件地址；解析失败或 userinfo 返回 null。 */
    private fun normalizeHttpUrl(rawUrl: String): String? {
        /** parsedUrl 交给 OkHttp 处理端口、IPv6 与编码细节。 */
        val parsedUrl = rawUrl.toHttpUrlOrNull() ?: return null
        /** supportedScheme 明确只允许真实 HTTP/HTTPS 传输。 */
        val supportedScheme = parsedUrl.scheme == "http" || parsedUrl.scheme == "https"
        if (!supportedScheme || parsedUrl.username.isNotEmpty() || parsedUrl.password.isNotEmpty()) return null
        return parsedUrl.toString()
    }
}
