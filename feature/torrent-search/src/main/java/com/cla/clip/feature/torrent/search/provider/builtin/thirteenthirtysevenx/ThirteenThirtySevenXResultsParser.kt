package com.cla.clip.feature.torrent.search.provider.builtin.thirteenthirtysevenx

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** 1337x 列表中已校验的详情草稿。 */
internal data class ThirteenThirtySevenXDraft(
    /** 非空列表名称。 */ val name: String,
    /** 已验证主站详情 URL。 */ val detailsPageUrl: String,
    /** 可选展示大小。 */ val size: String?,
    /** 可选做种数。 */ val seeders: UInt?,
    /** 可选节点数。 */ val peers: UInt?,
    /** 可选上传时间。 */ val uploadEpochMillis: Long?,
    /** 列表分类。 */ val category: Category?,
)

/** 1337x 列表 DOM parser 与详情合并器。 */
internal class ThirteenThirtySevenXResultsParser(
    /** 列表稳定 Provider 身份。 */ private val descriptor: ProviderDescriptor,
    /** 详情 URL 使用的主站 policy。 */ private val siteUrlPolicy: BuiltinProviderUrlPolicy,
    /** 列表日期使用的注入时钟。 */ currentEpochMillis: () -> Long,
    /** DOM、日期与领域映射计算调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 三形态列表日期 parser。 */
    private val dateParser = ThirteenThirtySevenXDateParser(currentEpochMillis)

    /** 解析固定列表行，不执行网络。 */
    suspend fun parse(
        /** 受限列表 HTML。 */ html: String,
        /** 已验证列表页基址。 */ pageUrl: String,
    ): List<ThirteenThirtySevenXDraft> = withContext(parsingDispatcher) {
        /** rows 是固定 table-list 的直接行。 */
        val rows = Jsoup.parse(html, pageUrl).select("table.table-list > tbody > tr")
        // row 是当前列表候选，协作遍历会先检查取消。
        rows.mapNotNullCancellable { row -> parseRow(row, pageUrl) }
    }

    /** 用详情身份补全列表结果并保留列表展示字段。 */
    suspend fun merge(
        /** 来源列表草稿。 */ draft: ThirteenThirtySevenXDraft,
        /** 完整合法详情。 */ details: TorrentDetails,
    ): Torrent = withContext(parsingDispatcher) {
        Torrent(
            infoHash = details.infoHash, name = draft.name, size = draft.size,
            seeders = draft.seeders, peers = draft.peers,
            providerId = descriptor.id, providerName = descriptor.displayName,
            uploadEpochMillis = draft.uploadEpochMillis, category = draft.category,
            descriptionPageUrl = draft.detailsPageUrl, magnetUri = details.magnetUri,
            fileDownloadLink = details.fileDownloadLink,
        )
    }

    /** 名称和主站详情 URL 必填，其他字段独立降级。 */
    private fun parseRow(
        /** 当前列表行。 */ row: Element,
        /** 当前列表页 URL。 */ pageUrl: String,
    ): ThirteenThirtySevenXDraft? {
        /** nameAnchor 是名称格第二个 anchor。 */
        val nameAnchor = row.selectFirst("td.name > a:nth-child(2)") ?: return null
        /** name 去边界空白后必须非空。 */
        val name = nameAnchor.ownText().trim().takeIf(String::isNotEmpty) ?: return null
        /** detailsPageUrl 必须同站 HTTPS。 */
        val detailsPageUrl = runCatching { siteUrlPolicy.resolveOptionalHttpsUrl(pageUrl, nameAnchor.attr("href")) }
            .getOrNull() ?: return null
        /** categoryHref 只用于离线提取固定 sub ID。 */
        val categoryHref = row.selectFirst("td.name > a:nth-child(1)")?.attr("href")
        /** categoryId 必须来自精确 `/sub/{digits}/...` 路径。 */
        val categoryId = categoryHref?.let { href -> CATEGORY_PATH.matchEntire(href)?.groupValues?.get(1) }
        return ThirteenThirtySevenXDraft(
            name = name, detailsPageUrl = detailsPageUrl,
            size = row.selectFirst("td.size")?.ownText()
                // character 是大小展示中的分组逗号，删除后保留单位与数值。
                ?.filterNot { character -> character == ',' }
                ?.trim()?.takeIf(String::isNotEmpty),
            seeders = row.selectFirst("td.seeds")?.ownText()?.trim()?.toUIntOrNull(),
            peers = row.selectFirst("td.leeches")?.ownText()?.trim()?.toUIntOrNull(),
            uploadEpochMillis = row.selectFirst("td.coll-date")?.ownText()?.let(dateParser::parseListDate),
            category = categoryId?.let(ThirteenThirtySevenXCategoryMapper::fromCategoryId),
        )
    }

    /** 列表路径常量。 */
    private companion object {
        /** 分类 ID 必须是首个 sub 路径段。 */
        private val CATEGORY_PATH = Regex("^/sub/(\\d+)(?:/.*)?$")
    }
}

/** 1337x 三种列表日期解析器，全部使用 UTC 与注入时钟。 */
internal class ThirteenThirtySevenXDateParser(
    /** 当前 UTC 毫秒，用于补充年或当日日期。 */ private val currentEpochMillis: () -> Long,
) {
    /** 严格解析显式两位年、当年日期时间或当日时间。 */
    fun parseListDate(
        /** 页面日期展示文本。 */ rawDate: String,
    ): Long? {
        /** normalizedDate 删除序数、点号和引号，并分隔紧邻 am/pm。 */
        val normalizedDate = rawDate.trim()
            .replace(ORDINAL_SUFFIX, "$1")
            .replace(AM_PM_BOUNDARY, "$1 $2")
            .replace(".", "")
            .replace("'", "")
        /** calendar 从注入 now 读取 UTC 年月日。 */
        val calendar = Calendar.getInstance(UTC, Locale.US).apply { timeInMillis = currentEpochMillis() }
        return when {
            EXPLICIT_YEAR.matches(normalizedDate) ->
                ProviderDateParser.parseUtcEpochMillis(normalizedDate, "MMM d yy")
            MONTH_DAY_TIME.matches(normalizedDate) -> {
                /** currentYear 是注入时钟对应 UTC 年。 */
                val currentYear = calendar.get(Calendar.YEAR)
                ProviderDateParser.parseUtcEpochMillis("$normalizedDate $currentYear", "h a MMM d yyyy")
            }
            TIME_ONLY.matches(normalizedDate) -> {
                /** currentDate 固定使用注入时钟的 UTC 日。 */
                val currentDate = String.format(
                    Locale.US, "%04d-%02d-%02d",
                    calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH),
                )
                ProviderDateParser.parseUtcEpochMillis("$currentDate $normalizedDate", "yyyy-MM-dd h:mm a")
                    ?: ProviderDateParser.parseUtcEpochMillis("$currentDate $normalizedDate", "yyyy-MM-dd h a")
            }
            else -> null
        }
    }

    /** 日期形状常量。 */
    private companion object {
        /** UTC 时区不读取设备设置。 */
        private val UTC = TimeZone.getTimeZone("UTC")
        /** 去除数字后的英文序数后缀。 */
        private val ORDINAL_SUFFIX = Regex("(\\d+)(st|nd|rd|th)", RegexOption.IGNORE_CASE)
        /** 把紧邻数字的 am/pm 分成独立 token。 */
        private val AM_PM_BOUNDARY = Regex("(\\d)(am|pm)\\b", RegexOption.IGNORE_CASE)
        /** 月日与两位年。 */
        private val EXPLICIT_YEAR = Regex("^[A-Za-z]{3} \\d{1,2} \\d{2}$")
        /** 时间、月份和日，年从注入时钟补齐。 */
        private val MONTH_DAY_TIME = Regex("^\\d{1,2}(?::\\d{2})? (?:am|pm) [A-Za-z]{3} \\d{1,2}$", RegexOption.IGNORE_CASE)
        /** 只有时间，日期从注入时钟补齐。 */
        private val TIME_ONLY = Regex("^\\d{1,2}(?::\\d{2})? (?:am|pm)$", RegexOption.IGNORE_CASE)
    }
}
