package com.cla.clip.feature.torrent.search.provider.builtin.uindex

import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** UIndex 详情页无网络 parser。 */
internal class UIndexDetailsParser(
    /** Provider 稳定身份来源。 */ private val descriptor: ProviderDescriptor,
    /** 海报等可选同站地址策略。 */ private val urlPolicy: UIndexUrlPolicy,
    /** DOM、日期与领域映射调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 名称与合法 magnet 必填；括号日期完整裁剪后按 UTC 严格解析。 */
    suspend fun parse(
        /** 有大小上限的 HTML。 */ html: String,
        /** 已验证详情页地址。 */ pageUrl: String,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** document 仅静态解析，不执行脚本。 */ val document = Jsoup.parse(html, pageUrl)
        /** name 必须为非空标题文本。 */ val name = document.selectFirst(".dt-title")?.ownText()?.trim()?.takeIf(String::isNotEmpty) ?: return@withContext null
        /** magnetUri 是固定下载按钮 href。 */ val magnetUri = document.selectFirst("a.dt-download-btn")?.attr("href") ?: return@withContext null
        /** infoHash 必须由公共解析器验证。 */ val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return@withContext null
        TorrentDetails(
            infoHash = infoHash,
            name = name,
            size = document.selectFirst(SIZE)?.ownText()?.trim()?.takeIf(String::isNotEmpty),
            seeders = document.selectFirst(".dt-seed")?.ownText()?.replace(",", "")?.trim()?.toUIntOrNull(),
            peers = document.selectFirst(".dt-leech")?.ownText()?.replace(",", "")?.trim()?.toUIntOrNull(),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = document.selectFirst(UPLOAD_DATE)?.ownText()?.let(::parseBracketedDate),
            category = document.selectFirst(".sr-cat-badge")?.ownText()?.let(UIndexCategoryMapper::fromPageText),
            lastCheckedEpochMillis = document.selectFirst(PEERS_UPDATED)?.text()?.let(::parseBracketedDate),
            magnetUri = magnetUri,
            description = document.selectFirst(".dt-descr-body")?.html(),
            posterUrl = urlPolicy.optionalSiteUrl(pageUrl, document.selectFirst(".tmdb-poster > img")?.attr("src")),
        )
    }

    /** 只在完整一对括号存在时移除括号，再用固定 UTC 格式解析。 */
    private fun parseBracketedDate(
        /** 页面括号日期文本。 */ rawDate: String,
    ): Long? {
        /** trimmedDate 删除外围 HTML 空白。 */ val trimmedDate = rawDate.trim()
        if (!trimmedDate.startsWith('(') || !trimmedDate.endsWith(')')) return null
        /** dateBody 是完整括号内部日期。 */ val dateBody = trimmedDate.removeSurrounding("(", ")").trim()
        return ProviderDateParser.parseUtcEpochMillis(dateBody, "yyyy-MM-dd HH:mm:ss")
    }

    /** 固定详情 selector。 */
    private companion object {
        /** 大小值。 */ private const val SIZE = "#content > div.dt-info-card > div > div:nth-child(1) > div:nth-child(2) > span.dt-info-value"
        /** 上传日期值。 */ private const val UPLOAD_DATE = "#content > div.dt-info-card > div > div:nth-child(1) > div:nth-child(3) > span.dt-info-value > span.dt-info-dim"
        /** 节点更新时间值。 */ private const val PEERS_UPDATED = "#content > div.dt-info-card > div > div:nth-child(2) > div:nth-child(3) > span.dt-info-value > span.dt-info-dim"
    }
}
