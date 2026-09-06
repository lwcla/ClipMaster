package com.cla.clip.feature.torrent.search.provider.builtin.xxxtracker

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** XXXTracker 详情页独立 DOM parser，完整保留倒数行定位。 */
internal class XxxTrackerDetailsParser(
    /** Provider 稳定身份来源。 */
    private val descriptor: ProviderDescriptor,
    /** 同站选填 URL 与媒体策略。 */
    private val urlPolicy: XxxTrackerUrlPolicy,
    /** DOM 与领域映射计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 解析固定详情结构，坏可选字段不影响合法身份。 */
    suspend fun parse(
        /** 有共享大小上限的 HTML。 */
        html: String,
        /** 已审核详情页 URL。 */
        pageUrl: String,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** document 只静态解析 HTML，不执行脚本。 */
        val document = Jsoup.parse(html, pageUrl)
        /** name 是详情必填字段。 */
        val name = document.selectFirst(NAME)?.text()?.trim()?.takeIf(String::isNotEmpty)
            ?: return@withContext null
        /** magnetUri 是详情身份必填字段。 */
        val magnetUri = document.selectFirst(MAGNET_ANCHOR)?.attr("href")
            ?: return@withContext null
        /** infoHash 使用公共规范化和校验。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return@withContext null
        /** descriptionElement 会仅删除前三个直属元素。 */
        val descriptionElement = document.selectFirst(DESCRIPTION_CELL)
        /** posterElement 取首个直属图片，不要求它位于首个直属位置。 */
        val posterElement = descriptionElement?.children()
            /** child 是当前直属元素候选。 */
            ?.firstOrNull { child -> child.tagName() == "img" }
        /** posterUrl 在删除固定直属元素前使用真实 page base 解析。 */
        val posterUrl = urlPolicy.optionalUrl(pageUrl, posterElement?.attr("src"))
        /** removableChildren 是固定上源要求删除的前三个直属节点。 */
        val removableChildren = descriptionElement?.children()?.take(DIRECT_CHILDREN_TO_REMOVE).orEmpty()
        /** child 是当前待移除的直属占位节点。 */
        removableChildren.forEach { child -> child.remove() }
        /** rawDate 来自固定倒数第三行。 */
        val rawDate = document.selectFirst(UPLOAD_DATE)?.ownText()?.trim()
        /** normalizedDate 只接受完整 dd-MM-yyyy 宽度。 */
        val normalizedDate = rawDate?.takeIf(FULL_DATE_PATTERN::matches)
        return@withContext TorrentDetails(
            infoHash = infoHash,
            name = name,
            size = document.selectFirst(SIZE)?.text()?.substringBefore('(')?.trim()
                ?.takeIf(String::isNotEmpty),
            seeders = document.selectFirst(SEEDERS)?.text()?.trim()?.toUIntOrNull(),
            peers = document.selectFirst(PEERS)?.text()?.trim()?.toUIntOrNull(),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = normalizedDate?.let {
                /** date 是已通过宽度校验的完整日期。 */
                date ->
                ProviderDateParser.parseUtcEpochMillis(date, "dd-MM-yyyy")
            },
            category = Category.PORN,
            magnetUri = magnetUri,
            fileDownloadLink = urlPolicy.optionalUrl(
                pageUrl,
                document.selectFirst(FILE_ANCHOR)?.attr("href"),
            ),
            description = descriptionElement?.html(),
            posterUrl = posterUrl,
        )
    }

    /** XXXTracker 固定详情 selector 与日期格式边界。 */
    private companion object {
        /** 详情标题。 */
        private const val NAME = "#content > h1"
        /** 倒数第二行第二格为大小。 */
        private const val SIZE = "#details > tbody > tr:nth-last-child(2) > td:nth-child(2)"
        /** 倒数第七行第二格为做种数。 */
        private const val SEEDERS = "#details > tbody > tr:nth-last-child(7) > td:nth-child(2)"
        /** 倒数第六行第二格为下载人数。 */
        private const val PEERS = "#details > tbody > tr:nth-last-child(6) > td:nth-child(2)"
        /** 倒数第三行第二格为完整日期。 */
        private const val UPLOAD_DATE = "#details > tbody > tr:nth-last-child(3) > td:nth-child(2)"
        /** 页面首个 magnet anchor 为身份。 */
        private const val MAGNET_ANCHOR = "a[href^=\"magnet:?\"]"
        /** 下载区首个 anchor 为 torrent 文件。 */
        private const val FILE_ANCHOR = "#download > a:nth-child(1)"
        /** 首行第二格为海报和描述。 */
        private const val DESCRIPTION_CELL = "#details > tbody > tr:nth-child(1) > td:nth-child(2)"
        /** 只删除海报及其后两个直属占位元素。 */
        private const val DIRECT_CHILDREN_TO_REMOVE = 3
        /** 日期必须完整匹配 dd-MM-yyyy。 */
        private val FULL_DATE_PATTERN = Regex("^\\d{2}-\\d{2}-\\d{4}$")
    }
}
