package com.cla.clip.feature.torrent.search.provider.builtin.nyaafamily

import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

/** Nyaa 族详情页无网络 parser。 */
internal class NyaaFamilyDetailsParser(
    /** Provider 稳定身份。 */ private val descriptor: ProviderDescriptor,
    /** 当前站点精确 URL 边界。 */ private val urlPolicy: BuiltinProviderUrlPolicy,
    /** 当前站点类别策略。 */ private val categoryMapper: NyaaFamilyCategoryMapper,
    /** 信息卡精确 selector。 */ private val infoCardSelector: String,
    /** DOM 与领域映射调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 在计算调度器解析信息卡；名称或合法 magnet 缺失时返回 null。 */
    suspend fun parse(/** 有大小上限的 HTML。 */ html: String, /** 已校验页面 URL。 */ pageUrl: String): TorrentDetails? =
        withContext(parsingDispatcher) {
            /** document 只做静态 DOM 解析。 */ val document = Jsoup.parse(html, pageUrl)
            /** card 包含详情元数据，动作按钮仍从全页查找。 */ val card = document.selectFirst(infoCardSelector) ?: return@withContext null
            /** body 固定承载元数据四行。 */ val body = card.selectFirst("div.panel-body") ?: return@withContext null
            /** name 是详情必填展示名。 */ val name = card.selectFirst("div.panel-heading > h3")?.ownText()?.trim()?.takeIf(String::isNotEmpty) ?: return@withContext null
            /** magnetUri 按上源从全页查找。 */ val magnetUri = document.selectFirst("""a[href^="magnet:"]""")?.attr("href") ?: return@withContext null
            /** infoHash 必须合法。 */ val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return@withContext null
            /** categoryCell 可能包含一级和二级类别链接。 */ val categoryCell = body.selectFirst("div:nth-child(1) > div:nth-child(2)")
            /** categoryIds 保留 DOM 次序，Nyaa 由 mapper 处理第二级 6_2 优先。 */ val categoryIds = categoryCell?.select("a")?.map { link -> detailsQueryCategory(link.absUrl("href")) }
            /** categoryId 优先选择游戏二级，否则首个结构化值。 */ val categoryId = categoryIds?.firstOrNull { id -> id == "6_2" } ?: categoryIds?.firstOrNull()
            TorrentDetails(
                infoHash = infoHash,
                name = name,
                size = body.selectFirst("div:nth-child(4) > div:nth-child(2)")?.ownText()?.trim()?.takeIf(String::isNotEmpty),
                seeders = body.selectFirst("div:nth-child(2) > div:nth-child(4)")?.text()?.trim()?.toUIntOrNull(),
                peers = body.selectFirst("div:nth-child(3) > div:nth-child(4)")?.text()?.trim()?.toUIntOrNull(),
                providerId = descriptor.id,
                providerName = descriptor.displayName,
                uploadEpochMillis = body.selectFirst("div:nth-child(1) > div:nth-child(4)")?.attr("data-timestamp")?.toLongOrNull()?.let(ProviderDateParser::epochSecondsToMillis),
                category = categoryMapper.map(categoryId),
                uploader = body.selectFirst("div:nth-child(2) > div:nth-child(2)")?.text()?.trim()?.takeIf(String::isNotEmpty),
                magnetUri = magnetUri,
                fileDownloadLink = optionalUrl(pageUrl, document.selectFirst("""a[href^="/download"]""")?.attr("href")),
                description = document.selectFirst("#torrent-description")?.html(),
            )
        }

    /** 安全策略拒绝的可选链接降级为空。 */
    private fun optionalUrl(/** 可信基址。 */ baseUrl: String, /** 未信任候选。 */ candidate: String?): String? =
        runCatching { urlPolicy.resolveOptionalHttpsUrl(baseUrl, candidate) }.getOrNull()
}

/** 从已解析 URL 的 query 中精确读取 c 参数。 */
private fun detailsQueryCategory(/** Jsoup 解析后的绝对 URL。 */ url: String): String? =
    url.toHttpUrlOrNull()?.queryParameter("c")
