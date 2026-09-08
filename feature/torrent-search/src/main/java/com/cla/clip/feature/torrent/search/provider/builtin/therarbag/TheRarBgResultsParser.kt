package com.cla.clip.feature.torrent.search.provider.builtin.therarbag

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.TorrentFileSizeFormatter
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** TheRarBg 列表中已通过网络前校验的详情草稿。 */
internal data class TheRarBgDraft(
    /** 非空列表名称。 */ val name: String,
    /** 已验证同站详情 URL。 */ val detailsPageUrl: String,
    /** 列表字节数格式化结果。 */ val size: String?,
    /** 可选做种数。 */ val seeders: UInt?,
    /** 可选节点数。 */ val peers: UInt?,
    /** 可选上传时间。 */ val uploadEpochMillis: Long?,
    /** 页面分类。 */ val category: Category?,
)

/** TheRarBg 列表 DOM parser 与详情合并器。 */
internal class TheRarBgResultsParser(
    /** 列表稳定 Provider 身份。 */ private val descriptor: ProviderDescriptor,
    /** 详情候选精确同站 policy。 */ private val urlPolicy: BuiltinProviderUrlPolicy,
    /** DOM 与领域映射计算调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 解析合法列表草稿，不在计算调度器执行网络。 */
    suspend fun parse(
        /** 受限列表 HTML。 */ html: String,
        /** 已验证列表页基址。 */ pageUrl: String,
    ): List<TheRarBgDraft> = withContext(parsingDispatcher) {
        /** rows 只包含固定 class 的直接列表项。 */
        val rows = Jsoup.parse(html, pageUrl).select("table > tbody > tr.list-entry")
        // row 是当前列表候选，协作遍历会先检查取消。
        rows.mapNotNullCancellable { row -> parseRow(row, pageUrl) }
    }

    /** 用详情身份补全列表结果，列表展示字段保持优先。 */
    suspend fun merge(
        /** 来源列表草稿。 */ draft: TheRarBgDraft,
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

    /** 名称和同站详情 URL 必填，其他字段独立降级。 */
    private fun parseRow(
        /** 当前列表行。 */ row: Element,
        /** 当前列表页 URL。 */ pageUrl: String,
    ): TheRarBgDraft? {
        /** nameAnchor 同时承载名称和详情链接。 */
        val nameAnchor = row.selectFirst("td.cellName > div > a") ?: return null
        /** name 去除边界空白后必须非空。 */
        val name = nameAnchor.ownText().trim().takeIf(String::isNotEmpty) ?: return null
        /** detailsPageUrl 跨站或非法时整条草稿跳过，避免详情请求。 */
        val detailsPageUrl = runCatching { urlPolicy.resolveOptionalHttpsUrl(pageUrl, nameAnchor.attr("href")) }
            .getOrNull() ?: return null
        /** rawSizeBytes 只接受非负 Long。 */
        val rawSizeBytes = row.selectFirst("td.sizeCell")?.attr("data-order")?.toLongOrNull()?.takeIf { bytes -> bytes >= 0L }
        /** epochSeconds 只接受 Long，转换溢出时为空。 */
        val epochSeconds = row.selectFirst("td:nth-child(4)")?.attr("data-order")?.toLongOrNull()
        return TheRarBgDraft(
            name = name, detailsPageUrl = detailsPageUrl,
            size = rawSizeBytes?.let(TorrentFileSizeFormatter::formatBytes),
            seeders = row.selectFirst("td:nth-child(7)")?.ownText()?.trim()?.toUIntOrNull(),
            peers = row.selectFirst("td:nth-child(8)")?.ownText()?.trim()?.toUIntOrNull(),
            uploadEpochMillis = epochSeconds?.let(ProviderDateParser::epochSecondsToMillis),
            category = row.selectFirst("td:nth-child(3) > a")?.ownText()?.let(TheRarBgCategoryMapper::fromPageText),
        )
    }
}
