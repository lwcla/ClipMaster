package com.cla.clip.feature.torrent.search.provider.builtin.torrentdatabase

import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.normalizeProviderDisplaySize
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** TorrentDatabase 详情页无网络 parser。 */
internal class TorrentDatabaseDetailsParser(
    /** Provider 稳定身份来源。 */ private val descriptor: ProviderDescriptor,
    /** DOM、日期与领域映射调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 名称和合法 magnet 必填；偏移上传时间与无偏移 UTC 时间使用独立严格路径。 */
    suspend fun parse(
        /** 有大小上限的 HTML。 */ html: String,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** document 仅静态解析，不执行脚本。 */ val document = Jsoup.parse(html)
        /** name 必须是非空详情标题。 */ val name = document.selectFirst(NAME)?.ownText()?.trim()?.takeIf(String::isNotEmpty) ?: return@withContext null
        /** magnetUri 来自固定下载按钮。 */ val magnetUri = document.selectFirst("#downloadMagnetBtn")?.attr("href") ?: return@withContext null
        /** infoHash 必须由公共解析器验证和规范化。 */ val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return@withContext null
        TorrentDetails(
            infoHash = infoHash,
            name = name,
            size = document.selectFirst(SIZE)?.ownText()?.let(::normalizeProviderDisplaySize),
            seeders = document.selectFirst(SEEDERS)?.ownText()?.trim()?.toUIntOrNull(),
            peers = document.selectFirst(PEERS)?.ownText()?.trim()?.toUIntOrNull(),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = document.selectFirst(UPLOAD_DATE)?.ownText()?.let(::parseUploadDate),
            category = document.selectFirst(".cat-badge")?.ownText()?.let(TorrentDatabaseCategoryMapper::fromPageText),
            uploader = document.selectFirst(UPLOADER)?.ownText()?.trim()?.takeIf(String::isNotEmpty),
            /** date 是不会进入日志的最后检查日期文本。 */
            lastCheckedEpochMillis = document.selectFirst(LAST_CHECKED)?.ownText()?.let { date -> ProviderDateParser.parseUtcEpochMillis(date, "yyyy-MM-dd HH:mm:ss") },
            magnetUri = magnetUri,
            description = document.selectFirst("div.torrent-info-card > div.torrent-info-content")?.html(),
        )
    }

    /** 显式 ±HH:mm/Z 先重组 ISO 解析，无时区才回退严格 UTC。 */
    private fun parseUploadDate(
        /** 页面上传时间。 */ rawDate: String,
    ): Long? {
        /** normalizedDate 去除协议值两端空白。 */ val normalizedDate = rawDate.trim()
        /** offsetMatch 精确拆分日期、时间和 ISO 时区。 */ val offsetMatch = OFFSET_DATE_PATTERN.matchEntire(normalizedDate)
        if (offsetMatch != null) {
            /** isoDate 用 T 连接日期时间以复用公共 ISO parser。 */ val isoDate = "${offsetMatch.groupValues[1]}T${offsetMatch.groupValues[2]}${offsetMatch.groupValues[3]}"
            return ProviderDateParser.parseIsoEpochMillis(isoDate)
        }
        return ProviderDateParser.parseUtcEpochMillis(normalizedDate, "yyyy-MM-dd HH:mm:ss")
    }

    /** 固定详情 selector 与日期结构。 */
    private companion object {
        /** 详情标题。 */ private const val NAME = "div.torrent-detail-card > div.card-header.torrent-cat-header > h4"
        /** 大小值。 */ private const val SIZE = "div.torrent-detail-card > div.card-body ul.torrent-info-list > li:nth-child(2) > strong.db-value"
        /** 做种数。 */ private const val SEEDERS = "div.torrent-detail-card > div.card-body ul.torrent-stats-list > li:nth-child(1) > strong.text-success"
        /** 节点数。 */ private const val PEERS = "div.torrent-detail-card > div.card-body ul.torrent-stats-list > li:nth-child(2) > strong.text-danger"
        /** 上传时间。 */ private const val UPLOAD_DATE = "div.torrent-detail-card > div.card-body ul.torrent-info-list > li:nth-child(3) > strong.db-value"
        /** 发布者。 */ private const val UPLOADER = "div.torrent-detail-card > div.card-body ul.torrent-info-list > li:nth-child(4) > a"
        /** 最后检查时间。 */ private const val LAST_CHECKED = "div.torrent-detail-card > div.card-body ul.torrent-info-list li:nth-child(5) > strong.db-value"
        /** 允许空格或 T 分隔，且时区必须为 Z 或带冒号偏移。 */
        private val OFFSET_DATE_PATTERN = Regex("^(\\d{4}-\\d{2}-\\d{2})[ T](\\d{2}:\\d{2}:\\d{2})(Z|[+-]\\d{2}:\\d{2})$")
    }
}
