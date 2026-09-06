package com.cla.clip.feature.torrent.search.provider.builtin.mypornclub

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** MyPornClub 列表页经必填校验后的无网络 draft。 */
internal data class MyPornClubDraft(
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
)

/** MyPornClub 列表 DOM parser 与领域合并器，不持有网络。 */
internal class MyPornClubResultsParser(
    /** Provider 元数据。 */
    private val descriptor: ProviderDescriptor,
    /** URL 安全策略。 */
    private val urlPolicy: MyPornClubUrlPolicy,
    /** 相对日期基准时钟。 */
    private val currentTimeMillis: () -> Long,
    /** DOM 与领域映射计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 将固定列表元素解析为已校验 draft。 */
    suspend fun parse(
        /** 有大小上限的 HTML。 */
        html: String,
        /** 已验证页面 URL。 */
        pageUrl: String,
    ): List<MyPornClubDraft> = withContext(parsingDispatcher) {
        /** document 只静态解析 HTML。 */
        val document = Jsoup.parse(html, pageUrl)
        document.select("div.torrents_list > div.torrent_element")
            .mapNotNullCancellable {
                /** row 是当前待解析列表项。 */ row -> parseRow(row, pageUrl)
            }
    }

    /** 在计算调度器合并列表优先字段与详情身份字段。 */
    suspend fun merge(
        /** 原列表 draft。 */
        draft: MyPornClubDraft,
        /** 已验证详情。 */
        details: TorrentDetails,
    ): Torrent = withContext(parsingDispatcher) {
        Torrent(
            infoHash = details.infoHash,
            name = draft.name,
            size = draft.size,
            seeders = draft.seeders,
            peers = draft.peers,
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = draft.uploadEpochMillis,
            category = Category.PORN,
            descriptionPageUrl = draft.detailsPageUrl,
            magnetUri = details.magnetUri,
            fileDownloadLink = details.fileDownloadLink,
        )
    }

    /** 名称和安全详情地址必填，其他字段独立降级。 */
    private fun parseRow(
        /** 当前列表项。 */
        row: Element,
        /** 页面基址。 */
        pageUrl: String,
    ): MyPornClubDraft? {
        /** nameAnchor 同时承载标题与详情链接。 */
        val nameAnchor = row.selectFirst(
            "div.torrent_element_text_div > a:nth-child(2)",
        ) ?: return null
        /** name 必须非空。 */
        val name = nameAnchor.selectFirst("span.torrent_element_text_span")
            ?.ownText()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        /** detailsUrl 必须在详情请求前完成校验。 */
        val detailsUrl = urlPolicy.optionalUrl(pageUrl, nameAnchor.attr("href"))
            ?: return null
        return MyPornClubDraft(
            name = name,
            size = row.selectFirst("div.torrent_element_info > span.teiv:nth-child(4)")
                ?.ownText()
                ?.trim()
                ?.takeIf(String::isNotEmpty),
            seeders = row.selectFirst("span.teiv_seeders")?.ownText()?.trim()?.toUIntOrNull(),
            peers = row.selectFirst("span.teiv_leechers")?.ownText()?.trim()?.toUIntOrNull(),
            uploadEpochMillis = row.selectFirst(
                "div.torrent_element_info > span.teiv:nth-child(2)",
            )?.text()?.let {
                /** rawDate 是列表相对日期原文。 */ rawDate ->
                ProviderDateParser.parseRelativeEpochMillis(rawDate, currentTimeMillis())
            },
            detailsPageUrl = detailsUrl,
        )
    }
}

/** MyPornClub 详情 DOM parser，不持有网络。 */
internal class MyPornClubDetailsParser(
    /** Provider 元数据。 */
    private val descriptor: ProviderDescriptor,
    /** URL 安全策略。 */
    private val urlPolicy: MyPornClubUrlPolicy,
    /** 相对日期基准时钟。 */
    private val currentTimeMillis: () -> Long,
    /** DOM 与领域映射计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 名称和 hash 必填，magnet 缺失时用公共固定 tracker 创建。 */
    suspend fun parse(
        /** 有大小上限的 HTML。 */
        html: String,
        /** 已验证页面 URL。 */
        pageUrl: String,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** document 只静态解析且不执行脚本。 */
        val document = Jsoup.parse(html, pageUrl)
        /** infoHash 从固定前缀字段读取并公共规范化。 */
        val infoHash = document.selectFirst("div.torrent_info_div > div:nth-child(1)")
            ?.ownText()
            ?.removePrefix("[hash_info]:")
            ?.trim()
            ?.let(MagnetInfoHashParser::normalize)
            ?: return@withContext null
        /** name 取 hash marker 前文本并拒绝空白。 */
        val name = document.selectFirst("div.torrent_text")
            ?.text()
            ?.substringBefore('#')
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return@withContext null
        /** pageMagnet 是可选页面磁力候选。 */
        val pageMagnet = document.selectFirst("a.md_btn")?.attr("href")?.takeIf(String::isNotBlank)
        /** magnetUri 缺失则生成，存在则必须合法且身份一致。 */
        val magnetUri = if (pageMagnet == null) {
            MagnetInfoHashParser.create(infoHash)
        } else {
            /** candidate 是待核对 hash 身份的页面 magnet。 */
            pageMagnet.takeIf { candidate -> MagnetInfoHashParser.parse(candidate) == infoHash }
                ?: return@withContext null
        }
        TorrentDetails(
            infoHash = infoHash,
            name = name,
            size = document.selectFirst("span.tsize_span")
                ?.ownText()
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.uppercase(Locale.ROOT),
            seeders = document.selectFirst("span.teiv_seeders")?.ownText()?.trim()?.toUIntOrNull(),
            peers = document.selectFirst("span.teiv_leechers")?.ownText()?.trim()?.toUIntOrNull(),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = relativeField(document, "div.torrent_info_div > div:nth-child(9)", "[uploaded]:"),
            category = Category.PORN,
            uploader = document.selectFirst("span.uploader_nick")
                ?.ownText()
                ?.removePrefix("@")
                ?.trim()
                ?.takeIf(String::isNotEmpty),
            lastCheckedEpochMillis = relativeField(
                document,
                "div.torrent_info_div > div:nth-child(8)",
                "[last checked]:",
            ),
            magnetUri = magnetUri,
            fileDownloadLink = urlPolicy.optionalUrl(
                pageUrl,
                document.selectFirst("a.td_btn")?.attr("href"),
            ),
        )
    }

    /** 读取带固定前缀的相对日期字段。 */
    private fun relativeField(
        /** 页面文档。 */
        document: org.jsoup.nodes.Document,
        /** 固定字段 selector。 */
        selector: String,
        /** 固定文本前缀。 */
        prefix: String,
    ): Long? {
        /** rawDate 去除固定前缀和边界空白。 */
        val rawDate = document.selectFirst(selector)
            ?.ownText()
            ?.removePrefix(prefix)
            ?.trim()
            ?: return null
        return ProviderDateParser.parseRelativeEpochMillis(rawDate, currentTimeMillis())
    }
}
