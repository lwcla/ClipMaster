package com.cla.clip.feature.torrent.search.provider.builtin.therarbag

import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** TheRarBg 固定标签详情 parser；名称和合法 magnet 必填。 */
internal class TheRarBgDetailsParser(
    /** 详情稳定 Provider 身份。 */ private val descriptor: ProviderDescriptor,
    /** DOM、日期与领域映射计算调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 解析详情页，坏可选字段独立降级。 */
    suspend fun parse(
        /** 受限详情 HTML。 */ html: String,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** document 只用于静态 DOM 解析。 */
        val document = Jsoup.parse(html)
        /** name 必须是指定 h4 的非空直属文本。 */
        val name = document.selectFirst("div.postContL > h4:has(+ div.table-responsive)")
            ?.ownText()?.trim()?.takeIf(String::isNotEmpty) ?: return@withContext null
        /** magnetUri 取首个 magnet anchor。 */
        val magnetUri = document.selectFirst("a[href^=\"magnet:?\"]")?.attr("href") ?: return@withContext null
        /** infoHash 必须可规范化。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return@withContext null
        /** detailRows 按固定 th 标签保存对应 td。 */
        val detailRows = document.select("table.detailTable > tbody > tr").mapNotNull { row ->
            /** label 是当前行的直属标题。 */
            val label = row.selectFirst("th")?.ownText()?.trim() ?: return@mapNotNull null
            /** value 是当前行的值格。 */
            val value = row.selectFirst("td") ?: return@mapNotNull null
            label to value
        }.toMap()
        /** peerParts 需要两个独立人数片段。 */
        val peerParts = detailRows["Peers:"]?.ownText()?.split(',', limit = 2)
        TorrentDetails(
            infoHash = infoHash, name = name,
            size = detailRows["Size:"]?.ownText()?.trim()?.takeIf(String::isNotEmpty),
            seeders = peerParts?.getOrNull(0)?.trim()?.removePrefix("Seeders: ")?.toUIntOrNull(),
            peers = peerParts?.getOrNull(1)?.trim()?.removePrefix("Leechers: ")?.toUIntOrNull(),
            providerId = descriptor.id, providerName = descriptor.displayName,
            uploadEpochMillis = detailRows["Added:"]?.ownText()?.let(::parseUploadDate),
            category = detailRows["Category:"]?.text()?.let(TheRarBgCategoryMapper::fromPageText),
            uploader = detailRows["Uploader:"]?.text()?.trim()?.takeIf(String::isNotEmpty),
            magnetUri = magnetUri,
            description = detailRows["Description:"]?.wholeText()?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    /** 严格归一化上源 `a.m.`/`p.m.` 时间并按 UTC 解析。 */
    private fun parseUploadDate(
        /** 页面 Added 文本。 */ rawDate: String,
    ): Long? {
        /** match 拆分月、日、年、时分和上午下午。 */
        val match = ADDED_DATE_PATTERN.matchEntire(rawDate.trim()) ?: return null
        /** rawTime 允许上源省略分钟。 */
        val rawTime = match.groupValues[4]
        /** fixedTime 缺分钟时补 `:00`。 */
        val fixedTime = if (':' in rawTime) rawTime else "$rawTime:00"
        /** meridiem 显式转成 SimpleDateFormat 接受的 AM/PM。 */
        val meridiem = if (match.groupValues[5] == "a.m.") "AM" else "PM"
        /** normalizedDate 保留日后的逗号以匹配固定格式。 */
        val normalizedDate = "${match.groupValues[1]} ${match.groupValues[2]}, ${match.groupValues[3]}, $fixedTime $meridiem"
        return ProviderDateParser.parseUtcEpochMillis(normalizedDate, "MMM d, yyyy, h:mm a")
    }

    /** TheRarBg 日期常量。 */
    private companion object {
        /** 上源允许小时或小时:分钟，其他尾随文本拒绝。 */
        private val ADDED_DATE_PATTERN = Regex("^([A-Za-z]{3}) (\\d{1,2}), (\\d{4}) (\\d{1,2}(?::\\d{2})?) (a\\.m\\.|p\\.m\\.)$")
    }
}
