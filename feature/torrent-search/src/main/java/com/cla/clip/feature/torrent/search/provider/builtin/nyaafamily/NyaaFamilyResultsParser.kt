package com.cla.clip.feature.torrent.search.provider.builtin.nyaafamily

import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Nyaa 族列表页无网络 parser。 */
internal class NyaaFamilyResultsParser(
    /** Provider 稳定身份。 */ private val descriptor: ProviderDescriptor,
    /** 当前站点精确 URL 边界。 */ private val urlPolicy: BuiltinProviderUrlPolicy,
    /** 当前站点类别策略。 */ private val categoryMapper: NyaaFamilyCategoryMapper,
    /** DOM 与领域映射调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 在计算调度器解析固定结果表，未匹配时返回空列表。 */
    suspend fun parse(/** 有大小上限的 HTML。 */ html: String, /** 已校验页面 URL。 */ pageUrl: String): List<Torrent> =
        withContext(parsingDispatcher) {
            /** document 只做静态 DOM 解析。 */ val document = Jsoup.parse(html, pageUrl)
            document.select("table.torrent-list > tbody > tr").mapNotNullCancellable { row -> parseRow(row, pageUrl) }
        }

    /** 名称和合法 magnet/btih 必填，可选 URL 失败时单字段降级。 */
    private fun parseRow(/** 当前列表行。 */ row: Element, /** 当前页面基址。 */ pageUrl: String): Torrent? {
        /** titleAnchor 排除评论链接。 */ val titleAnchor = row.selectFirst("td:nth-child(2) > a:not(.comments)") ?: return null
        /** name 必须非空。 */ val name = titleAnchor.ownText().trim().takeIf(String::isNotEmpty) ?: return null
        /** magnetUri 按固定第三列第二个链接读取。 */ val magnetUri = row.selectFirst("td:nth-child(3) > a:nth-child(2)")?.attr("href") ?: return null
        /** infoHash 验证 torrent 身份。 */ val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return null
        /** categoryId 只从结构化 query 读取。 */ val categoryId = row.selectFirst("td:nth-child(1) > a")?.absUrl("href")?.let(::resultQueryCategory)
        return Torrent(
            infoHash = infoHash,
            name = name,
            size = row.selectFirst("td:nth-child(4)")?.ownText()?.trim()?.takeIf(String::isNotEmpty),
            seeders = row.selectFirst("td:nth-child(6)")?.ownText()?.trim()?.toUIntOrNull(),
            peers = row.selectFirst("td:nth-child(7)")?.ownText()?.trim()?.toUIntOrNull(),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = row.selectFirst("td:nth-child(5)")?.attr("data-timestamp")?.toLongOrNull()?.let(ProviderDateParser::epochSecondsToMillis),
            category = categoryMapper.map(categoryId),
            descriptionPageUrl = optionalUrl(pageUrl, titleAnchor.attr("href")),
            magnetUri = magnetUri,
            fileDownloadLink = optionalUrl(pageUrl, row.selectFirst("td:nth-child(3) > a:nth-child(1)")?.attr("href")),
        )
    }

    /** 安全策略拒绝的可选链接降级为空。 */
    private fun optionalUrl(/** 可信基址。 */ baseUrl: String, /** 未信任候选。 */ candidate: String?): String? =
        runCatching { urlPolicy.resolveOptionalHttpsUrl(baseUrl, candidate) }.getOrNull()
}

/** 从已解析 URL 的 query 中精确读取 c 参数。 */
private fun resultQueryCategory(/** Jsoup 解析后的绝对 URL。 */ url: String): String? =
    url.toHttpUrlOrNull()?.queryParameter("c")
