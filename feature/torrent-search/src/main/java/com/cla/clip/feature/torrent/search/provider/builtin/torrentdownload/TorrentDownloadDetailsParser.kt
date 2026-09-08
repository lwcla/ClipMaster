package com.cla.clip.feature.torrent.search.provider.builtin.torrentdownload

import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** TorrentDownload 详情页无网络 parser。 */
internal class TorrentDownloadDetailsParser(
    /** 详情稳定 Provider 身份。 */ private val descriptor: ProviderDescriptor,
    /** 可选 torrent 文件精确 host 策略。 */ private val fileUrlPolicy: BuiltinProviderUrlPolicy,
    /** DOM、日期与领域映射计算调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 名称和合法 magnet 必填，可选文件 URL 失败只独立降级。 */
    suspend fun parse(
        /** 受统一大小限制的详情 HTML。 */ html: String,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** document 仅静态读取固定表格。 */
        val document = Jsoup.parse(html)
        /** tableBody 是详情字段的固定根容器。 */
        val tableBody = "table.torrentinfo > tbody"
        /** name 必须是非空详情标题。 */
        val name = document.selectFirst("$tableBody > tr:nth-child(1) > td:nth-child(2)")
            ?.ownText()?.trim()?.takeIf(String::isNotEmpty) ?: return@withContext null
        /** magnetUri 必须含可规范化 btih。 */
        val magnetUri = document.selectFirst("a[href^=\"magnet:?\"]")?.attr("href") ?: return@withContext null
        /** infoHash 是公开结果的稳定身份。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return@withContext null
        /** fileCandidate 只匹配 manifest 审计的文件 host。 */
        val fileCandidate = document.selectFirst("a[href^=\"https://itorrent.net/\"]")?.attr("href")
        /** fileDownloadLink 非法时不影响 magnet 详情。 */
        val fileDownloadLink = runCatching { fileCandidate?.let(fileUrlPolicy::requireHttpsUrl)?.toString() }.getOrNull()
        TorrentDetails(
            infoHash = infoHash,
            name = name,
            size = document.selectFirst("$tableBody > tr:nth-child(6) > td:nth-child(2)")?.ownText()?.trim()?.takeIf(String::isNotEmpty),
            seeders = document.selectFirst("$tableBody > tr:nth-child(5) > td:nth-child(2) > span:nth-child(1)")
                ?.ownText()?.removePrefix("Seeds: ")?.trim()?.toUIntOrNull(),
            peers = document.selectFirst("$tableBody > tr:nth-child(5) > td:nth-child(2) > span:nth-child(2)")
                ?.ownText()?.removePrefix("Leechers: ")?.trim()?.toUIntOrNull(),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = document.selectFirst("$tableBody > tr:nth-child(8) > td:nth-child(2)")
                ?.ownText()
                /** rawDate 是详情页严格日期原文，仅在内存解析。 */
                ?.let { rawDate ->
                    ProviderDateParser.parseUtcEpochMillis(rawDate, "d MMMM yyyy")
                },
            category = document.selectFirst("$tableBody > tr:nth-child(4) > td:nth-child(2)")?.ownText()
                ?.let(TorrentDownloadCategoryMapper::fromPageText),
            magnetUri = magnetUri,
            fileDownloadLink = fileDownloadLink,
        )
    }
}
