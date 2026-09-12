package com.cla.clip.feature.torrent.search.provider.builtin.xxxclub

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** XXXClub 列表中已完成必填和安全校验的详情草稿。 */
internal data class XXXClubDraft(
    /** 非空列表名称。 */ val name: String,
    /** 可选展示大小。 */ val size: String?,
    /** 可选做种数。 */ val seeders: UInt?,
    /** 可选节点数。 */ val peers: UInt?,
    /** 严格 UTC 上传时间。 */ val uploadEpochMillis: Long?,
    /** 已验证主站详情 URL。 */ val detailsPageUrl: String,
)

/** XXXClub 列表 DOM parser 与详情合并器，不持有网络。 */
internal class XXXClubResultsParser(
    /** 列表稳定 Provider 身份。 */ private val descriptor: ProviderDescriptor,
    /** 详情 URL 使用的主站 policy。 */ private val siteUrlPolicy: BuiltinProviderUrlPolicy,
    /** DOM、日期与领域映射计算调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 解析固定列表容器内的直接候选项。 */
    suspend fun parse(
        /** 受限列表 HTML。 */ html: String,
        /** 已验证列表页基址。 */ pageUrl: String,
    ): List<XXXClubDraft> = withContext(parsingDispatcher) {
        /** container 兼容固定上游记录的两种列表容器；缺失时是合法空结果。 */
        val container = Jsoup.parse(html, pageUrl).selectFirst("div.browsetableinside, div.divtableinside")
            ?: return@withContext emptyList()
        /** rows 只读取容器内固定 `ul > li` 结果。 */
        val rows = container.select("ul > li")
        // row 是当前列表候选，协作遍历会先检查取消。
        rows.mapNotNullCancellable { row -> parseRow(row, pageUrl) }
    }

    /** 用详情身份补全列表结果并保留列表展示字段。 */
    suspend fun merge(
        /** 来源列表草稿。 */ draft: XXXClubDraft,
        /** 完整合法详情。 */ details: TorrentDetails,
    ): Torrent = withContext(parsingDispatcher) {
        Torrent(
            infoHash = details.infoHash,
            name = draft.name,
            size = draft.size,
            seeders = draft.seeders,
            peers = draft.peers,
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = draft.uploadEpochMillis,
            category = Category.PORN,
            descriptionPageUrl = draft.detailsPageUrl,
            magnetUri = details.magnetUri,
            fileDownloadLink = details.fileDownloadLink,
        )
    }

    /** 名称和安全详情地址必填，其他列表字段独立降级。 */
    private fun parseRow(
        /** 当前列表项。 */ row: Element,
        /** 已验证列表页基址。 */ pageUrl: String,
    ): XXXClubDraft? {
        /** nameAnchor 同时承载固定详情路径和展示名称。 */
        val nameAnchor = row.selectFirst("span:nth-child(2) > a[href^=/torrents/details]") ?: return null
        /** name 去除边界空白后必须非空。 */
        val name = nameAnchor.text().trim().takeIf(String::isNotEmpty) ?: return null
        /** detailsPageUrl 在任何详情网络请求前完成精确同站校验。 */
        val detailsPageUrl = runCatching {
            siteUrlPolicy.resolveOptionalHttpsUrl(pageUrl, nameAnchor.attr("href"))
        }.getOrNull() ?: return null
        return XXXClubDraft(
            name = name,
            size = row.selectFirst("span.siz")?.ownText()?.trim()?.takeIf(String::isNotEmpty),
            seeders = row.selectFirst("span.see")?.ownText()?.trim()?.toUIntOrNull(),
            peers = row.selectFirst("span.lee")?.ownText()?.trim()?.toUIntOrNull(),
            uploadEpochMillis = row.selectFirst("span.adde")?.ownText()?.let {
                /** rawDate 是固定无时区站点字段，按 UTC 严格解析。 */ rawDate ->
                ProviderDateParser.parseUtcEpochMillis(rawDate, DATE_FORMAT)
            },
            detailsPageUrl = detailsPageUrl,
        )
    }

    /** 列表协议常量。 */
    private companion object {
        /** 固定上游日期格式，解析时统一使用 UTC。 */
        private const val DATE_FORMAT = "dd MMM yyyy HH:mm:ss"
    }
}
