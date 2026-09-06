package com.cla.clip.feature.torrent.search.provider.builtin.nekobt

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** NekoBT 列表页无网络 parser。 */
internal class NekoBtResultsParser(
    /** Provider 稳定身份。 */ private val descriptor: ProviderDescriptor,
    /** 同站可选 URL 策略。 */ private val urlPolicy: BuiltinProviderUrlPolicy,
    /** DOM 与领域映射调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 解析固定表格；selector 未匹配时返回空列表。 */
    suspend fun parse(/** 有大小上限的 HTML。 */ html: String, /** 已校验页面 URL。 */ pageUrl: String): List<Torrent> =
        withContext(parsingDispatcher) {
            /** document 只做静态 DOM 解析。 */ val document = Jsoup.parse(html, pageUrl)
            document.select("table.table > tbody > tr").mapNotNullCancellable { row -> parseRow(row, pageUrl) }
        }

    /** 名称和合法 magnet/btih 必填。 */
    private fun parseRow(/** 当前结果行。 */ row: Element, /** 当前页面基址。 */ pageUrl: String): Torrent? {
        /** titleAnchor 承载详情地址与嵌套标题。 */ val titleAnchor = row.selectFirst("td:nth-child(3) > div:nth-child(1) > div > a") ?: return null
        /** name 精确读取首个嵌套 span 的 ownText。 */ val name = titleAnchor.selectFirst("span > span:nth-child(1)")?.ownText()?.trim()?.takeIf(String::isNotEmpty) ?: return null
        /** magnetUri 是第四列第一个动作。 */ val magnetUri = row.selectFirst("td:nth-child(4) > div > a:nth-child(1)")?.attr("href") ?: return null
        /** infoHash 验证 torrent 身份。 */ val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return null
        return Torrent(
            infoHash = infoHash,
            name = name,
            size = row.selectFirst("td:nth-child(5) > span")?.ownText()?.trim()?.takeIf(String::isNotEmpty),
            seeders = row.selectFirst("td:nth-child(7) > span")?.ownText()?.trim()?.toUIntOrNull(),
            peers = row.selectFirst("td:nth-child(8) > span")?.ownText()?.trim()?.toUIntOrNull(),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            /** rawDate 是列表页固定 UTC 日期文本。 */
            uploadEpochMillis = row.selectFirst("td:nth-child(6) > span")?.ownText()?.trim()?.let { rawDate -> ProviderDateParser.parseUtcEpochMillis(rawDate, "yyyy-MM-dd HH:mm:ss") },
            category = Category.ANIME,
            descriptionPageUrl = optionalUrl(pageUrl, titleAnchor.attr("href")),
            magnetUri = magnetUri,
            fileDownloadLink = optionalUrl(pageUrl, row.selectFirst("td:nth-child(4) > div > a:nth-child(2)")?.attr("href")),
        )
    }

    /** 安全策略拒绝的可选链接只降级对应字段。 */
    private fun optionalUrl(/** 可信基址。 */ baseUrl: String, /** 未信任候选。 */ candidate: String?): String? =
        runCatching { urlPolicy.resolveOptionalHttpsUrl(baseUrl, candidate) }.getOrNull()
}
