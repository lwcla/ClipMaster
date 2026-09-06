package com.cla.clip.feature.torrent.search.provider.builtin.torrentkitty

import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** TorrentKitty 列表页独立 DOM parser，保留 hash-only 输出契约。 */
internal class TorrentKittyResultsParser(
    /** Provider 稳定身份来源。 */
    private val descriptor: ProviderDescriptor,
    /** 同站选填 URL 策略。 */
    private val urlPolicy: TorrentKittyUrlPolicy,
    /** DOM 与领域映射计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 丢弃 archiveResult 首行后按原顺序解析合法条目。 */
    suspend fun parse(
        /** 有共享大小上限的 HTML。 */
        html: String,
        /** 已审核列表页 URL。 */
        pageUrl: String,
    ): List<Torrent> = withContext(parsingDispatcher) {
        /** document 只静态解析 HTML，不执行脚本。 */
        val document = Jsoup.parse(html, pageUrl)
        document.select(LIST_ITEMS)
            .drop(1)
            /** row 是当前固定列表行。 */
            .mapNotNullCancellable { row -> parseRow(row, pageUrl) }
    }

    /** 名称与第二 action anchor 的合法 hash 必填。 */
    private fun parseRow(
        /** 当前列表行。 */
        row: Element,
        /** 当前列表页基址。 */
        pageUrl: String,
    ): Torrent? {
        /** name 必须是非空名称格文本。 */
        val name = row.selectFirst(NAME_CELL)?.ownText()?.trim()?.takeIf(String::isNotEmpty)
            ?: return null
        /** magnetCandidate 只用于解析 hash，不进入列表输出。 */
        val magnetCandidate = row.selectFirst(MAGNET_ANCHOR)?.attr("href") ?: return null
        /** infoHash 使用公共规则规范化并校验。 */
        val infoHash = MagnetInfoHashParser.parse(magnetCandidate) ?: return null
        /** rawDate 是严格 yyyy-MM-dd 候选。 */
        val rawDate = row.selectFirst(DATE_CELL)?.ownText()?.trim()
        return Torrent(
            infoHash = infoHash,
            name = name,
            size = row.selectFirst(SIZE_CELL)?.ownText()?.trim()?.takeIf(String::isNotEmpty)
                ?.uppercase(Locale.ROOT),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = parseDate(rawDate),
            descriptionPageUrl = urlPolicy.optionalUrl(
                pageUrl,
                row.selectFirst(DETAILS_ANCHOR)?.attr("href"),
            ),
            fileDownloadLink = urlPolicy.optionalUrl(
                pageUrl,
                row.selectFirst(FILE_ANCHOR)?.attr("href"),
            ),
        )
    }

    /** 严格解析 yyyy-MM-dd UTC 日期，坏值返回 null。 */
    private fun parseDate(
        /** 原始列表日期。 */
        rawDate: String?,
    ): Long? {
        /** normalizedDate 只接受完整固定宽度。 */
        val normalizedDate = rawDate?.takeIf(DATE_PATTERN::matches) ?: return null
        return ProviderDateParser.parseUtcEpochMillis(normalizedDate, "yyyy-MM-dd")
    }

    /** TorrentKitty 固定列表 selector。 */
    private companion object {
        /** archiveResult 表头和结果行。 */
        private const val LIST_ITEMS = "table#archiveResult > tbody > tr"
        /** 名称格。 */
        private const val NAME_CELL = "td.name"
        /** 大小格。 */
        private const val SIZE_CELL = "td.size"
        /** 日期格。 */
        private const val DATE_CELL = "td.date"
        /** action 第一 anchor 为详情。 */
        private const val DETAILS_ANCHOR = "td.action > a:nth-child(1)"
        /** action 第二 anchor 为 magnet/hash。 */
        private const val MAGNET_ANCHOR = "td.action > a:nth-child(2)"
        /** action 第三 anchor 为 torrent 文件。 */
        private const val FILE_ANCHOR = "td.action > a:nth-child(3)"
        /** 日期必须完整匹配 yyyy-MM-dd。 */
        private val DATE_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    }
}
