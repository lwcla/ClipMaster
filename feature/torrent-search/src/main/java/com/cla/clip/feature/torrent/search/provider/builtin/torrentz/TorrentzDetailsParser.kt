package com.cla.clip.feature.torrent.search.provider.builtin.torrentz

import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** Torrentz 详情页无网络 parser。 */
internal class TorrentzDetailsParser(
    /** 详情稳定 Provider 身份。 */ private val descriptor: ProviderDescriptor,
    /** 相对 torrent 文件使用的主站 policy。 */ private val siteUrlPolicy: BuiltinProviderUrlPolicy,
    /** DOM、日期与领域映射计算调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 名称和合法 magnet 必填，可选字段独立降级。 */
    suspend fun parse(
        /** 受统一大小限制的详情 HTML。 */ html: String,
        /** 已验证详情页基址。 */ pageUrl: String,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** document 仅静态解析固定 download 区域。 */
        val document = Jsoup.parse(html, pageUrl)
        /** name 必须是非空详情标题。 */
        val name = document.selectFirst("div.download > h2")?.ownText()?.trim()?.takeIf(String::isNotEmpty)
            ?: return@withContext null
        /** magnetUri 必须来自 download 容器且含合法 btih。 */
        val magnetUri = document.selectFirst("div.download a[href^=\"magnet:?\"]")?.attr("href")
            ?: return@withContext null
        /** infoHash 统一规范化为稳定小写 hex。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return@withContext null
        /** fileCandidate 保留固定上游拼写错误的 `/dowload/torrent/` 选择器。 */
        val fileCandidate = document.selectFirst("div.download a[href^=\"/dowload/torrent/\"]")?.attr("href")
        /** fileDownloadLink 必须解析为精确同站 HTTPS。 */
        val fileDownloadLink = runCatching { siteUrlPolicy.resolveOptionalHttpsUrl(pageUrl, fileCandidate) }.getOrNull()
        TorrentDetails(
            infoHash = infoHash,
            name = name,
            size = valueAfterLabel(document, "Size"),
            seeders = valueAfterLabel(document, "Seeds")?.toUIntOrNull(),
            peers = valueAfterLabel(document, "Leechers")?.toUIntOrNull(),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = valueAfterLabel(document, "Added")
                /** rawDate 是详情页严格日期原文，仅在内存解析。 */
                ?.let { rawDate ->
                    ProviderDateParser.parseUtcEpochMillis(rawDate, "M/d/yyyy")
                },
            category = document.selectFirst("div.download > p:last-child > a")?.attr("href")
                ?.substringAfterLast('=', missingDelimiterValue = "")?.toIntOrNull()
                ?.let(TorrentzCategoryMapper::fromId),
            magnetUri = magnetUri,
            fileDownloadLink = fileDownloadLink,
        )
    }

    /** 按固定英文标签读取相邻表格值。 */
    private fun valueAfterLabel(
        /** 已静态解析的详情 DOM。 */ document: org.jsoup.nodes.Document,
        /** 固定英文标签。 */ label: String,
    ): String? = document.selectFirst("td:containsOwn($label)")?.nextElementSibling()
        ?.ownText()?.trim()?.takeIf(String::isNotEmpty)
}
