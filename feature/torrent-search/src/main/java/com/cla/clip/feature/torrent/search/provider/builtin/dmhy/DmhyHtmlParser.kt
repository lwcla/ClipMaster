package com.cla.clip.feature.torrent.search.provider.builtin.dmhy

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import com.cla.clip.feature.torrent.search.provider.builtin.common.normalizeProviderDisplaySize
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Dmhy 静态 HTML 解析；可选危险链接丢弃，必填 magnet 无效则跳过，不执行脚本。 */
internal class DmhyHtmlParser(
    /** 稳定来源字段。 */
    private val descriptor: ProviderDescriptor,
    /** 相对链接必须通过与请求相同的来源策略。 */
    private val urlPolicy: BuiltinProviderUrlPolicy,
    /** DOM、日期与领域映射的计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 固定 table 缺失表示结构异常，合法空 tbody 返回空列表。 */
    suspend fun parseResults(
        /** 网络层已经执行正文大小限制。 */
        html: String,
        /** 已验证页面地址，供相对链接解析。 */
        pageUrl: String,
    ): List<Torrent> = withContext(parsingDispatcher) {
        /** Jsoup 只建立静态 DOM。 */
        val document = Jsoup.parse(html, pageUrl)
        if (document.selectFirst("table#topic_list") == null) throw ProviderParseException()
        /** row 是当前条目，每条开始前公共遍历检查取消。 */
        document.select("table#topic_list > tbody > tr").mapNotNullCancellable { row -> parseRow(row, pageUrl) }
    }

    /** 名称与合法 magnet 必填，可选数字、日期和详情 URL 独立降级。 */
    private fun parseRow(row: Element, pageUrl: String): Torrent? {
        /** 标题 anchor 同时给出可选详情链接。 */
        val anchor = row.selectFirst("td.title > a") ?: return null
        /** 空标题不形成有效条目。 */
        val name = anchor.ownText().takeIf(String::isNotBlank) ?: return null
        /** magnet 保留原 URI，但身份必须由公共解析器校验。 */
        val magnet = row.selectFirst("td:nth-child(4) > a:nth-child(1)")?.attr("href") ?: return null
        /** 统一稳定 hash 用于跨源去重。 */
        val hash = MagnetInfoHashParser.parse(magnet) ?: return null
        return Torrent(
            infoHash = hash, name = name, providerId = descriptor.id, providerName = descriptor.displayName,
            magnetUri = magnet,
            size = row.selectFirst("td:nth-child(5)")?.ownText()?.let(::normalizeProviderDisplaySize),
            seeders = row.selectFirst("td:nth-child(6)")?.text()?.toUIntOrNull(),
            peers = row.selectFirst("td:nth-child(7)")?.text()?.toUIntOrNull(),
            /** rawDate 为站点无时区日期，以固定 UTC 解释。 */
            uploadEpochMillis = row.selectFirst("td:nth-child(1) > span")?.ownText()?.let { rawDate ->
                ProviderDateParser.parseUtcEpochMillis(rawDate, "yyyy/MM/dd HH:mm")
            },
            category = DmhyCategoryMapper.fromId(row.selectFirst("td:nth-child(2) > a")?.className()?.removePrefix("sort-")),
            descriptionPageUrl = optionalUrl(pageUrl, anchor.attr("href")),
        )
    }

    /** 详情缺必填返回 null；描述去除固定前两个子节点，保留剩余不可信 HTML。 */
    suspend fun parseDetails(
        /** 已限量 HTML，不进入日志。 */
        html: String,
        /** 可信基址用于类别链接。 */
        pageUrl: String,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** 每次调用独立 DOM，节点删除不会影响其他请求。 */
        val document = Jsoup.parse(html, pageUrl)
        /** 名称是成功详情的必填展示内容。 */
        val name = document.selectFirst("div.topic-title > h3")?.text()?.takeIf(String::isNotBlank)
            ?: return@withContext null
        /** 原始 magnet 只向领域模型传递。 */
        val magnet = document.selectFirst("div#resource-tabs > div#tabs-1 > p:nth-child(2) > a#a_magnet")
            ?.attr("href") ?: return@withContext null
        /** 不接受非法身份的详情。 */
        val hash = MagnetInfoHashParser.parse(magnet) ?: return@withContext null
        /** 描述节点在当前 DOM 内去前两个元素，HTML 始终不能当脚本执行。 */
        val description = document.selectFirst("div.topic-nfo")
        /** child 是固定前缀元素，只从本次 DOM 删除。 */
        description?.children()?.take(2)?.forEach { child -> child.remove() }
        /** 分类链接仅同站固定路径有意义，其余保守映射其他。 */
        val categoryLink = document.selectFirst("div.topic-title > div.info > ul > li:nth-child(1) > span > a")?.attr("href")
        return@withContext TorrentDetails(
            infoHash = hash, name = name, providerId = descriptor.id, providerName = descriptor.displayName,
            magnetUri = magnet,
            size = document.selectFirst("div.topic-title > div.info > ul > li:nth-child(6) > span")?.text()?.let(::normalizeProviderDisplaySize),
            /** rawDate 的首个空白前日期保留固定上源行为。 */
            uploadEpochMillis = document.selectFirst("div.topic-title > div.info > ul > li:nth-child(2) > span")?.text()?.let { rawDate ->
                /** character 用于找到日期与后续时间之间的首个空白。 */
                ProviderDateParser.parseUtcEpochMillis(rawDate.takeWhile { character -> !character.isWhitespace() }, "yyyy/MM/dd")
            },
            category = categoryFromLink(pageUrl, categoryLink),
            description = description?.html(),
        )
    }

    /** 可选链接错误只丢弃字段，不因旁支外链损失合法 magnet 结果。 */
    private fun optionalUrl(pageUrl: String, candidate: String?): String? =
        runCatching { urlPolicy.resolveOptionalHttpsUrl(pageUrl, candidate) }.getOrNull()

    /** 精确路径、无查询与片段的同站分类链接才参与映射。 */
    private fun categoryFromLink(pageUrl: String, candidate: String?): Category {
        /** 已校验的可选分类地址。 */
        val url = optionalUrl(pageUrl, candidate)?.toHttpUrl() ?: return Category.OTHER
        if (url.pathSegments.size != 4 || url.pathSegments.take(3) != listOf("topics", "list", "sort_id") ||
            url.query != null || url.fragment != null
        ) return Category.OTHER
        return DmhyCategoryMapper.fromId(url.pathSegments.last())
    }
}
