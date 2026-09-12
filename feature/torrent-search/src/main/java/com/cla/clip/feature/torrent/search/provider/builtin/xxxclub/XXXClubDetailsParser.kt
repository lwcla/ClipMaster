package com.cla.clip.feature.torrent.search.provider.builtin.xxxclub

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** XXXClub 详情 DOM parser；名称与合法 magnet 身份必填。 */
internal class XXXClubDetailsParser(
    /** 详情稳定 Provider 身份。 */ private val descriptor: ProviderDescriptor,
    /** 文件与海报使用的主站 policy。 */ private val siteUrlPolicy: BuiltinProviderUrlPolicy,
    /** DOM、日期与领域映射计算调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 解析固定详情字段，可选字段失败只清空自身。 */
    suspend fun parse(
        /** 受限详情 HTML。 */ html: String,
        /** 已验证主站详情页基址。 */ pageUrl: String,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** document 只静态解析 HTML，不执行脚本。 */
        val document = Jsoup.parse(html, pageUrl)
        /** name 是固定详情标题的必填非空文本。 */
        val name = document.selectFirst("body > div > div.middle > div.main-content > div > h1")
            ?.ownText()?.trim()?.takeIf(String::isNotEmpty) ?: return@withContext null
        /** magnetUri 是详情稳定身份的必填来源。 */
        val magnetUri = document.selectFirst("a[href^=\"magnet:?\"]")?.attr("href") ?: return@withContext null
        /** infoHash 统一为小写 40 位 hex，坏 magnet 不进入领域层。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return@withContext null
        /** fileCandidate 是不可信可选 torrent 文件链接。 */
        val fileCandidate = document.selectFirst(
            "div.detailsdescr > ul > li.downloadboxlist > span:nth-child(1) > a",
        )?.attr("href")
        /** posterCandidate 是不可信可选海报链接。 */
        val posterCandidate = document.selectFirst("img.detailsposter")?.attr("src")
        TorrentDetails(
            infoHash = infoHash,
            name = name,
            size = document.selectFirst("div.detailsdescr > ul > li:nth-child(2) > span:nth-child(3)")
                ?.ownText()?.trim()?.takeIf(String::isNotEmpty),
            seeders = document.selectFirst("div.detailsdescr font.see")?.ownText()?.trim()?.toUIntOrNull(),
            peers = document.selectFirst("div.detailsdescr font.lee")?.ownText()?.trim()?.toUIntOrNull(),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = strictDateField(
                document.selectFirst("div.detailsdescr > ul > li:nth-child(3) > span:nth-child(3)")?.ownText(),
            ),
            category = Category.PORN,
            uploader = document.selectFirst("div.detailsdescr > ul > li:nth-child(6) > span:nth-child(3)")
                ?.ownText()?.trim()?.takeIf(String::isNotEmpty),
            lastCheckedEpochMillis = strictDateField(
                document.selectFirst("div.detailsdescr > ul > li:nth-child(5) > span:nth-child(3)")?.ownText(),
            ),
            magnetUri = magnetUri,
            fileDownloadLink = optionalSameSiteUrl(pageUrl, fileCandidate),
            description = document.selectFirst("div.description")?.html(),
            posterUrl = optionalSameSiteUrl(pageUrl, posterCandidate),
        )
    }

    /** 严格解析 UTC 日期；Pending 或污染文本返回 null。 */
    private fun strictDateField(
        /** 可空站点日期文本。 */ rawDate: String?,
    ): Long? = rawDate?.let { date -> ProviderDateParser.parseUtcEpochMillis(date, DATE_FORMAT) }

    /** 可选媒体只接受当前主站 HTTPS，非法值按字段级降级为空。 */
    private fun optionalSameSiteUrl(
        /** 已验证详情页基址。 */ pageUrl: String,
        /** 不可信媒体候选。 */ candidate: String?,
    ): String? = runCatching { siteUrlPolicy.resolveOptionalHttpsUrl(pageUrl, candidate) }.getOrNull()

    /** 详情协议常量。 */
    private companion object {
        /** 固定上游日期格式，解析时统一使用 UTC。 */
        private const val DATE_FORMAT = "dd MMM yyyy HH:mm:ss"
    }
}
