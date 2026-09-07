package com.cla.clip.feature.torrent.search.provider.builtin.tokyotoshokan

import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.normalizeProviderDisplaySize
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** TokyoToshokan 双行列表页无网络 parser。 */
internal class TokyoToshokanResultsParser(
    /** Provider 稳定身份来源。 */ private val descriptor: ProviderDescriptor,
    /** 类别和可选同站链接策略。 */ private val urlPolicy: TokyoToshokanUrlPolicy,
    /** DOM、日期与领域映射调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 以 desc-top 行为唯一记录起点，只消费紧邻 desc-bot，避免相邻窗口重叠。 */
    suspend fun parse(
        /** 有大小上限的 HTML。 */ html: String,
        /** 已验证页面地址。 */ pageUrl: String,
    ): List<Torrent> = withContext(parsingDispatcher) {
        /** rows 是表头后的原始行，保留 DOM 顺序。 */ val rows = Jsoup.parse(html, pageUrl).select("table.listing > tbody > tr:nth-child(n+2)")
        /** outputs 按页面顺序收集合法标题记录。 */ val outputs = mutableListOf<Torrent>()
        /** rowIndex 指向尚未处理的行。 */ var rowIndex = 0
        while (rowIndex < rows.size) {
            currentCoroutineContext().ensureActive()
            /** titleRow 只有包含精确 desc-top 单元格才是记录起点。 */ val titleRow = rows[rowIndex]
            if (titleRow.selectFirst("td.desc-top") == null) {
                rowIndex += 1
                continue
            }
            /** metadataRow 只能是紧邻且包含 desc-bot 的下一行，row 是待验证候选。 */
            val metadataRow = rows.getOrNull(rowIndex + 1)?.takeIf { row -> row.selectFirst("td.desc-bot") != null }
            /** parsedItem 缺必填身份时跳过当前记录。 */ val parsedItem = parseItem(titleRow, metadataRow, pageUrl)
            if (parsedItem != null) outputs += parsedItem
            rowIndex += if (metadataRow == null) 1 else 2
        }
        outputs
    }

    /** 从标题行读取身份，从可选元数据行按标签独立降级。 */
    private fun parseItem(
        /** 当前标题行。 */ titleRow: Element,
        /** 仅限紧邻的元数据行。 */ metadataRow: Element?,
        /** 当前已验证页面地址。 */ pageUrl: String,
    ): Torrent? {
        /** nameAnchor 是 desc-top 第二个 anchor，同时承载 torrent 文件候选。 */ val nameAnchor = titleRow.selectFirst("td.desc-top > a:nth-child(2)") ?: return null
        /** name 是业务必填展示身份。 */ val name = nameAnchor.ownText().trim().takeIf(String::isNotEmpty) ?: return null
        /** magnetUri 是第一项 anchor 的未记录原始 magnet。 */ val magnetUri = titleRow.selectFirst("td.desc-top > a:nth-child(1)")?.attr("href") ?: return null
        /** infoHash 由公共解析器校验并规范化。 */ val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return null
        /** metadataFields 只接受带明确标签的字段，不依赖固定数组位置。 */ val metadataFields = metadataRow?.selectFirst("td.desc-bot")?.ownText()?.split('|').orEmpty().map(String::trim)
        /** rawSize 仅匹配 Size 标签。 */ val rawSize = metadataFields.firstOrNull { field -> field.startsWith("Size:") }?.removePrefix("Size:")
        /** rawDate 仅匹配 Date 标签。 */ val rawDate = metadataFields.firstOrNull { field -> field.startsWith("Date:") }?.removePrefix("Date:")
        /** categoryId 只从精确 cat query 读取。 */ val categoryId = urlPolicy.optionalCategoryId(titleRow.selectFirst("td:nth-child(1) > a")?.attr("href"))
        return Torrent(
            infoHash = infoHash,
            name = name,
            size = rawSize?.let(::normalizeProviderDisplaySize),
            seeders = metadataRow?.selectFirst("td.stats > span:nth-child(1)")?.ownText()?.trim()?.toUIntOrNull(),
            peers = metadataRow?.selectFirst("td.stats > span:nth-child(2)")?.ownText()?.trim()?.toUIntOrNull(),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            /** date 是不会进入日志的列表日期文本。 */
            uploadEpochMillis = rawDate?.let { date -> ProviderDateParser.parseUtcEpochMillis(date, "yyyy-MM-dd HH:mm z") },
            category = categoryId?.let(TokyoToshokanCategoryMapper::fromPageId),
            descriptionPageUrl = urlPolicy.optionalSiteUrl(pageUrl, titleRow.selectFirst("td.web > a:last-child")?.attr("href")),
            magnetUri = magnetUri,
            fileDownloadLink = urlPolicy.optionalSiteUrl(pageUrl, nameAnchor.attr("href")),
        )
    }
}
