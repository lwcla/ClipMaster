package com.cla.clip.feature.torrent.search.provider.builtin.thirteenthirtysevenx

import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** 1337x 固定详情 DOM parser；主站媒体与 itorrents 文件使用独立 policy。 */
internal class ThirteenThirtySevenXDetailsParser(
    /** 详情稳定 Provider 身份。 */ private val descriptor: ProviderDescriptor,
    /** 海报使用的主站 policy。 */ private val siteUrlPolicy: BuiltinProviderUrlPolicy,
    /** torrent 文件使用的独立审计 host policy。 */ private val fileUrlPolicy: BuiltinProviderUrlPolicy,
    /** 相对日期使用的注入时钟。 */ private val currentEpochMillis: () -> Long,
    /** DOM、日期与领域映射计算调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 名称和合法 magnet 必填，其他字段独立降级。 */
    suspend fun parse(
        /** 受限详情 HTML。 */ html: String,
        /** 已验证主站详情页基址。 */ pageUrl: String,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** document 只静态解析不执行脚本。 */
        val document = Jsoup.parse(html, pageUrl)
        /** name 是详情标题必填值。 */
        val name = document.selectFirst("div.box-info.torrent-detail-page > div.box-info-heading > h1")
            ?.ownText()?.trim()?.takeIf(String::isNotEmpty) ?: return@withContext null
        /** magnetUri 是详情稳定身份来源。 */
        val magnetUri = document.selectFirst("a[href^=\"magnet:?\"]")?.attr("href") ?: return@withContext null
        /** infoHash 统一为小写 40 位 hex。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return@withContext null
        /** firstInfo 是固定详情第一列。 */
        val firstInfo = "div.box-info.torrent-detail-page > div:nth-child(2) > div:nth-child(1) > ul:nth-child(2)"
        /** secondInfo 是固定详情第二列。 */
        val secondInfo = "div.box-info.torrent-detail-page > div:nth-child(2) > div:nth-child(1) > ul:nth-child(3)"
        /** fileCandidate 只读取上源 itorrents anchor。 */
        val fileCandidate = document.selectFirst("a[href^=\"https://itorrents.org\"]")?.attr("href")
        /** posterCandidate 是不可信主站相对或绝对地址。 */
        val posterCandidate = document.selectFirst("div.torrent-image > img")?.attr("src")
        TorrentDetails(
            infoHash = infoHash, name = name,
            size = document.selectFirst("$firstInfo > li:nth-child(4) > span")?.ownText()
                // character 是大小展示中的分组逗号，删除后保持其余站点文本。
                ?.filterNot { character -> character == ',' }?.trim()?.takeIf(String::isNotEmpty),
            seeders = document.selectFirst("$secondInfo > li:nth-child(4) > span")?.ownText()?.trim()?.toUIntOrNull(),
            peers = document.selectFirst("$secondInfo > li:nth-child(5) > span")?.ownText()?.trim()?.toUIntOrNull(),
            providerId = descriptor.id, providerName = descriptor.displayName,
            uploadEpochMillis = document.selectFirst("$secondInfo > li:nth-child(3) > span")?.ownText()?.let(::parseRelativeDate),
            category = document.selectFirst("$firstInfo > li:nth-child(1) > span")?.ownText()?.let(ThirteenThirtySevenXCategoryMapper::fromPageText),
            uploader = document.selectFirst("$firstInfo > li:nth-child(5) > span")?.text()?.trim()?.takeIf(String::isNotEmpty),
            lastCheckedEpochMillis = document.selectFirst("$secondInfo > li:nth-child(2) > span")?.ownText()?.let(::parseRelativeDate),
            magnetUri = magnetUri,
            fileDownloadLink = optionalMatchingFileUrl(fileCandidate, infoHash),
            description = document.selectFirst("div#description")?.html(),
            posterUrl = runCatching { siteUrlPolicy.resolveOptionalHttpsUrl(pageUrl, posterCandidate) }.getOrNull(),
        )
    }

    /** 解析相对日期时每个字段使用同一次注入 now。 */
    private fun parseRelativeDate(
        /** 页面相对日期。 */ rawDate: String,
    ): Long? = ProviderDateParser.parseRelativeEpochMillis(rawDate, currentEpochMillis())

    /** torrent 文件必须来自 itorrents 精确路径且文件名 hash 与 magnet 一致。 */
    private fun optionalMatchingFileUrl(
        /** 页面下载候选。 */ candidate: String?,
        /** magnet 解析出的稳定 hash。 */ infoHash: String,
    ): String? {
        /** trustedUrl 先执行独立 host、HTTPS、端口和 userinfo 校验。 */
        val trustedUrl = runCatching { candidate?.let(fileUrlPolicy::requireHttpsUrl) }.getOrNull() ?: return null
        /** pathSegments 必须是 `/torrent/{hash}.torrent`。 */
        val pathSegments = trustedUrl.pathSegments
        if (
            pathSegments.size != 2 || pathSegments[0] != "torrent" ||
            trustedUrl.query != null || trustedUrl.fragment != null
        ) return null
        /** fileHash 从精确后缀前提取并规范化。 */
        val fileHash = pathSegments[1].takeIf { name -> name.endsWith(".torrent", ignoreCase = true) }
            ?.dropLast(".torrent".length)
            ?.let(MagnetInfoHashParser::normalize)
            ?: return null
        if (fileHash != infoHash) return null
        /** canonicalUrl 使用稳定小写 hash 重建文件名，避免同一资源产生大小写不同地址。 */
        val canonicalUrl = trustedUrl.newBuilder().setPathSegment(1, "$infoHash.torrent").build()
        return canonicalUrl.toString()
    }
}
