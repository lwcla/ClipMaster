package com.cla.clip.feature.torrent.search.provider.builtin.ext

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Ext 搜索页无网络 parser；先产受限 draft，补 magnet 后再映射领域结果。 */
internal class ExtResultsParser(
    /** Torrent 稳定 Provider 字段来源。 */
    private val descriptor: ProviderDescriptor,
    /** 详情链接的精确 HTTPS host 策略。 */
    urlPolicy: BuiltinProviderUrlPolicy,
    /** DOM、日期、btih 与领域映射计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** token、分类和模型 URL 的共享字段解析器。 */
    private val valueParser = ExtPageValueParser(urlPolicy)

    /** 搜索页必须包含固定 table；脚本仅按文本提取 token，不执行。 */
    suspend fun parse(html: String, pageUrl: String): ExtResultsEnvelope = withContext(parsingDispatcher) {
        /** Jsoup 静态 DOM 不执行 script。 */
        val document = Jsoup.parse(html, pageUrl)
        /** 固定顶层结果表缺失时抛无正文解析异常。 */
        val table = document.selectFirst("table.search-table") ?: throw ProviderParseException()
        /** CSRF session 只接受 1..1024 字符。 */
        val sessionId = valueParser.boundedToken(
            document.selectFirst("meta[name=csrf-token]")?.attr("content"),
        )
        /** 搜索 page token 只从 script data 的固定赋值语句读取。 */
        val pageToken = valueParser.extractScriptToken(document, "searchPageToken")
        /** row 是当前表格行，逐条解析前检查取消。 */
        val drafts = table.select("> tbody > tr").mapNotNullCancellable { row ->
            parseDraft(row, pageUrl)
        }
        ExtResultsEnvelope(sessionId, pageToken, drafts)
    }

    /** 将已补合法 magnet 的搜索 draft 映射为领域结果。 */
    suspend fun toTorrent(draft: ExtResultDraft, magnetUri: String): Torrent = withContext(parsingDispatcher) {
        /** magnet 已在 JSON parser 校验，解析出的 hash 是稳定业务键。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: throw ProviderParseException()
        Torrent(
            infoHash = infoHash,
            name = draft.name,
            size = draft.size,
            seeders = draft.seeders,
            peers = draft.peers,
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = draft.uploadEpochMillis,
            category = draft.category,
            descriptionPageUrl = draft.detailsPageUrl,
            magnetUri = magnetUri,
        )
    }

    /** 单条结果缺 ID、名称或同站详情地址时跳过，避免为无效 draft 请求 magnet。 */
    private fun parseDraft(row: Element, pageUrl: String): ExtResultDraft? {
        /** torrent ID 必须限制为 1..256。 */
        val torrentId = valueParser.boundedId(
            row.selectFirst("a.search-magnet-btn")?.attr("data-id"),
        ) ?: return null
        /** 标题 anchor 同时承载业务名称与详情地址。 */
        val titleAnchor = row.selectFirst("a.torrent-title-link") ?: return null
        /** 空名称不能形成结果。 */
        val name = titleAnchor.text().takeIf(String::isNotBlank) ?: return null
        /** 详情地址为结果必填；跨 host、明文或非法值使该条跳过。 */
        val detailsPageUrl = valueParser.resolveOptionalModelUrl(pageUrl, titleAnchor.attr("href")) ?: return null
        /** 上传日期只接受固定 UTC 格式，非法值降级为空。 */
        val uploadEpochMillis = row.selectFirst("td:nth-child(4) > div > span:nth-child(2)")
            ?.attr("title")
            // rawDate 是站点日期属性，只交给严格 UTC parser。
            ?.let { rawDate -> ProviderDateParser.parseUtcEpochMillis(rawDate, "dd MMMM yyyy") }
        return ExtResultDraft(
            torrentId = torrentId,
            name = name,
            size = row.selectFirst("td:nth-child(2) > div > span:nth-child(2)")?.ownText()?.takeIf(String::isNotBlank),
            seeders = row.selectFirst("td:nth-child(5) > div > span:nth-child(2)")?.ownText()?.toUIntOrNull(),
            peers = row.selectFirst("td:nth-child(6) > div > span:nth-child(2)")?.ownText()?.toUIntOrNull(),
            uploadEpochMillis = uploadEpochMillis,
            category = valueParser.parseCategory(
                row.selectFirst("td:nth-child(1) > div:nth-child(1) > div.related-posted > a:nth-child(2)")
                    ?.attr("href"),
                pageUrl,
            ),
            detailsPageUrl = detailsPageUrl,
        )
    }
}

/** Ext 搜索页第一阶段结果，token 与 DOM draft 不持有网络客户端。 */
internal data class ExtResultsEnvelope(
    /** 受限 CSRF session；缺失或超限时为空。 */
    val sessionId: String?,
    /** 受限搜索 page token；缺失或超限时为空。 */
    val pageToken: String?,
    /** 按 DOM 顺序保存的结构化结果。 */
    val drafts: List<ExtResultDraft>,
)

/** Ext 单条搜索 draft，只保存进入表单和领域模型所需的受限字段。 */
internal data class ExtResultDraft(
    /** 1..256 字符 torrent ID。 */
    val torrentId: String,
    /** 非空展示名称。 */
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
    /** manifest 精确 host 的详情 URL。 */
    val detailsPageUrl: String,
)
