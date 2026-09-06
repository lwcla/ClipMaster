package com.cla.clip.feature.torrent.search.provider.builtin.ext

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup

/** Ext 详情页无网络 parser；DOM draft、海报边界与领域映射保持独立。 */
internal class ExtDetailsParser(
    /** TorrentDetails 稳定 Provider 字段来源。 */
    private val descriptor: ProviderDescriptor,
    /** 类别和海报链接的精确 HTTPS host 策略。 */
    urlPolicy: BuiltinProviderUrlPolicy,
    /** 相对日期使用的可注入当前毫秒。 */
    private val currentTimeMillis: () -> Long,
    /** DOM、日期、btih 与领域映射计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** token、分类和模型 URL 的共享字段解析器。 */
    private val valueParser = ExtPageValueParser(urlPolicy)

    /** 详情页必须含固定 card；缺业务必填或超限字段返回 null。 */
    suspend fun parse(html: String, pageUrl: String): ExtDetailsDraft? = withContext(parsingDispatcher) {
        /** Jsoup 静态解析不执行页面脚本。 */
        val document = Jsoup.parse(html, pageUrl)
        /** 固定详情 card 缺失代表顶层协议异常。 */
        val card = document.selectFirst("div.detal-page-left-block > div.card > div.card-body")
            ?: throw ProviderParseException()
        /** torrent ID 按固定上游从 document 全局查找，按钮可位于详情 card 外且仍限制 1..256。 */
        val torrentId = valueParser.boundedId(
            document.selectFirst("a.detail-magnet-link.download-btn-magnet")?.attr("data-id"),
        ) ?: return@withContext null
        /** CSRF session 是详情表单必填。 */
        val sessionId = valueParser.boundedToken(
            document.selectFirst("meta[name=csrf-token]")?.attr("content"),
        ) ?: return@withContext null
        /** 详情 page token 只读取固定脚本文本。 */
        val pageToken = valueParser.extractScriptToken(document, "pageToken") ?: return@withContext null
        /** 名称是详情业务必填字段。 */
        val name = card.selectFirst(".card-title")?.ownText()?.takeIf(String::isNotBlank) ?: return@withContext null
        /** 类别链接只接受同站路径语义，非法值保守回退 OTHER。 */
        val category = valueParser.parseCategory(
            card.selectFirst("div.detail-torrent-poster-info > a:nth-child(3)")?.attr("href"),
            pageUrl,
        )
        /** 描述 slug 可选，但存在时必须限制 1..256。 */
        val torrentSlug = valueParser.boundedId(card.selectFirst("a.js-dscr")?.attr("data-code"))
        /** 海报候选只允许 ext.to 精确 HTTPS 且过滤固定占位图。 */
        val posterCandidate = (
            card.selectFirst("div.poster-block > a > img")
                ?: card.selectFirst("img.detail-torrent-image")
            )?.attr("src")
        /** 解析失败、跨 host 或固定占位图都按无海报降级。 */
        val posterUrl = filterPosterUrl(
            valueParser.resolveOptionalModelUrl(pageUrl, posterCandidate),
        )
        /** 上传日期只接受固定 UTC 格式。 */
        val uploadEpochMillis = card.selectFirst("div.detail-torrent-poster-info > span:nth-child(1)")
            ?.attr("title")
            // rawDate 是站点日期属性，只交给严格 UTC parser。
            ?.let { rawDate -> ProviderDateParser.parseUtcEpochMillis(rawDate, "dd MMMM yyyy") }
        /** 最后检查时间按注入时钟解析相对表达。 */
        val lastCheckedEpochMillis = card.selectFirst("span.detail-update-date > strong")
            ?.ownText()
            // rawDate 是站点相对日期文本，不读取系统当前时刻。
            ?.let { rawDate -> ProviderDateParser.parseRelativeEpochMillis(rawDate, currentTimeMillis()) }
        ExtDetailsDraft(
            torrentId = torrentId,
            sessionId = sessionId,
            pageToken = pageToken,
            name = name,
            size = card.selectFirst(".content-size")?.ownText()?.removePrefix("Size: ")?.takeIf(String::isNotBlank),
            seeders = card.selectFirst("#seed-counter")?.ownText()?.toUIntOrNull(),
            peers = card.selectFirst("#leech-counter")?.ownText()?.toUIntOrNull(),
            uploadEpochMillis = uploadEpochMillis,
            category = category,
            uploader = card.selectFirst("div.detail-torrent-poster-info span.external-user")
                ?.ownText()
                ?.takeIf(String::isNotBlank),
            lastCheckedEpochMillis = lastCheckedEpochMillis,
            torrentSlug = torrentSlug,
            moviePlot = card.selectFirst("div.movie-info > div.plot-block")?.ownText()?.takeIf(String::isNotBlank),
            seriesPlot = card.selectFirst("div.plot-info > div.block-plot-tv")?.ownText()?.takeIf(String::isNotBlank),
            posterUrl = posterUrl,
        )
    }

    /** 将详情 draft、合法 magnet 与可选描述映射为领域详情。 */
    suspend fun toDetails(
        /** 详情 DOM 的受限结构化字段。 */
        draft: ExtDetailsDraft,
        /** 已通过 btih 校验且保留原值的 magnet。 */
        magnetUri: String,
        /** DOM 或 AJAX 返回的不可信描述。 */
        description: String?,
    ): TorrentDetails = withContext(parsingDispatcher) {
        /** 详情 magnet 的规范 hash 是稳定主键。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: throw ProviderParseException()
        TorrentDetails(
            infoHash = infoHash,
            name = draft.name,
            size = draft.size,
            seeders = draft.seeders,
            peers = draft.peers,
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = draft.uploadEpochMillis,
            category = draft.category,
            uploader = draft.uploader,
            lastCheckedEpochMillis = draft.lastCheckedEpochMillis,
            magnetUri = magnetUri,
            description = description,
            posterUrl = draft.posterUrl,
        )
    }

    /** 海报必须仍是 ext.to HTTPS，固定 no-torrent-image.png 占位图返回 null。 */
    private fun filterPosterUrl(candidate: String?): String? {
        /** trustedCandidate 已由精确 host 策略校验，可安全解析路径。 */
        val trustedCandidate = candidate ?: return null
        if (trustedCandidate.toHttpUrl().encodedPath.endsWith("/no-torrent-image.png")) return null
        return trustedCandidate
    }
}

/** Ext 详情页第一阶段 draft；请求字段均在 DOM 解析阶段完成长度限制。 */
internal data class ExtDetailsDraft(
    /** 1..256 字符 torrent ID。 */
    val torrentId: String,
    /** 1..1024 字符 CSRF session。 */
    val sessionId: String,
    /** 1..1024 字符详情 page token。 */
    val pageToken: String,
    /** 非空详情名称。 */
    val name: String,
    /** 可选上源大小文本。 */
    val size: String?,
    /** 可选做种数。 */
    val seeders: UInt?,
    /** 可选下载人数。 */
    val peers: UInt?,
    /** 可选 UTC 上传毫秒。 */
    val uploadEpochMillis: Long?,
    /** 固定类别映射。 */
    val category: Category,
    /** 可选发布者文本。 */
    val uploader: String?,
    /** 可选 UTC 最后检查毫秒。 */
    val lastCheckedEpochMillis: Long?,
    /** 可选且最长 256 字符的描述 slug。 */
    val torrentSlug: String?,
    /** 电影类别直接使用的 DOM plot。 */
    val moviePlot: String?,
    /** 剧集类别直接使用的 DOM plot。 */
    val seriesPlot: String?,
    /** 仅允许 ext.to 精确 HTTPS 的海报 URL。 */
    val posterUrl: String?,
)