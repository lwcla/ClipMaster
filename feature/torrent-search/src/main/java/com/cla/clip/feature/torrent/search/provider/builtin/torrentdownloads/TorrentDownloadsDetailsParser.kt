package com.cla.clip.feature.torrent.search.provider.builtin.torrentdownloads

import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** TorrentDownloads 详情页无网络 parser。 */
internal class TorrentDownloadsDetailsParser(
    /** 详情稳定 Provider 身份。 */ private val descriptor: ProviderDescriptor,
    /** 相对 torrent 链接使用的主站 policy。 */ private val siteUrlPolicy: BuiltinProviderUrlPolicy,
    /** DOM、日期与领域映射计算调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 名称和合法 magnet 必填，其他字段独立降级。 */
    suspend fun parse(
        /** 受统一大小限制的详情 HTML。 */ html: String,
        /** 已验证详情页基址。 */ pageUrl: String,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** document 仅静态解析固定详情容器。 */
        val document = Jsoup.parse(html, pageUrl)
        /** name 必须是非空标题。 */
        val name = document.selectFirst("div.inner_container > h1.titl_1 > span")
            ?.ownText()?.trim()?.takeIf(String::isNotEmpty) ?: return@withContext null
        /** magnetUri 必须含合法 btih。 */
        val magnetUri = document.selectFirst("a[href^=\"magnet:?\"]")?.attr("href") ?: return@withContext null
        /** infoHash 是稳定公开身份。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return@withContext null
        /** fileCandidate 是同站相对或绝对可选下载链接。 */
        val fileCandidate = document.selectFirst("u.download > li:nth-child(2) > a")?.attr("href")
        /** fileDownloadLink 越界时只降级为空。 */
        val fileDownloadLink = runCatching { siteUrlPolicy.resolveOptionalHttpsUrl(pageUrl, fileCandidate) }.getOrNull()
        TorrentDetails(
            infoHash = infoHash,
            name = name,
            size = document.selectFirst("div.inner_container > div:nth-child(13) > p")?.ownText()?.trim()?.takeIf(String::isNotEmpty),
            seeders = document.selectFirst("div.inner_container > div:nth-child(15) > p")?.ownText()?.trim()?.toUIntOrNull(),
            peers = document.selectFirst("div.inner_container > div:nth-child(16) > p")?.ownText()?.trim()?.toUIntOrNull(),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = document.selectFirst("div.inner_container > div:nth-child(19) > p")?.ownText()
                /** rawDate 是详情页上传日期原文，仅在内存解析。 */
                ?.let { rawDate ->
                    ProviderDateParser.parseUtcEpochMillis(rawDate, DATE_PATTERN)
                },
            category = document.selectFirst("div.inner_container > h1:nth-child(1) > img")?.attr("src")
                ?.let(TorrentDownloadsCategoryMapper::fromIconUrl),
            lastCheckedEpochMillis = document.selectFirst("div.inner_container > div:nth-child(18) > p")?.ownText()
                ?.removeSuffix("()")?.trim()
                /** rawDate 是详情页最后检查日期原文，仅在内存解析。 */
                ?.let { rawDate ->
                    ProviderDateParser.parseUtcEpochMillis(rawDate, DATE_PATTERN)
                },
            magnetUri = magnetUri,
            fileDownloadLink = fileDownloadLink,
        )
    }

    /** 固定详情日期格式。 */
    private companion object {
        /** 上游使用严格 UTC 年月日时分秒。 */
        private const val DATE_PATTERN = "yyyy-MM-dd HH:mm:ss"
    }
}
