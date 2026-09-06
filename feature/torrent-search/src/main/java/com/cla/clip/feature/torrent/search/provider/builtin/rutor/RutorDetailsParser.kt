package com.cla.clip.feature.torrent.search.provider.builtin.rutor

import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Rutor 详情页独立 DOM parser，不持有网络。 */
internal class RutorDetailsParser(
    /** Provider 稳定身份来源。 */
    private val descriptor: ProviderDescriptor,
    /** 同站选填 URL 与媒体策略。 */
    private val urlPolicy: RutorUrlPolicy,
    /** DOM 与领域映射计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 按俄文 header 解析详情，并在输出描述前移除媒体容器。 */
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
        /** infoByHeader 保留固定俄文标签对应的第二格节点。 */
        val infoByHeader = document.select("table#details > tbody > tr:nth-child(n+2)")
            /** row 是当前俄文 header 行。 */
            .mapNotNullCancellable { row ->
                /** header 是当前字段的俄文稳定标签。 */
                val header = row.selectFirst("td.header")?.ownText()?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?: return@mapNotNullCancellable null
                /** value 是与 header 同行的第二格。 */
                val value = row.selectFirst("td:nth-child(2)")
                    ?: return@mapNotNullCancellable null
                header to value
            }
            .toMap()
        /** descriptionElement 在提取媒体后原位清理。 */
        val descriptionElement = document.selectFirst(DESCRIPTION_CELL)
        /** posterElement 只取首个直属图片，不能误认嵌套正文图。 */
        val posterElement = descriptionElement?.children()
            /** child 是当前直属元素候选。 */
            ?.firstOrNull { child -> child.tagName() == "img" }
        /** posterUrl 在移除直属海报前按同站策略解析。 */
        val posterUrl = urlPolicy.optionalUrl(pageUrl, posterElement?.attr("src"))
        /** screenshotUrls 从截图 textarea 二次静态解析并按同站策略过滤。 */
        val screenshotUrls = extractScreenshots(descriptionElement, pageUrl)
        posterElement?.remove()
        descriptionElement?.select("div.hidewrap")?.remove()
        /** categoryUrl 先按同站策略解析，再交给结构化 path mapper。 */
        val categoryUrl = urlPolicy.optionalUrl(
            pageUrl,
            infoByHeader["Категория"]?.selectFirst("a")?.attr("href"),
        )
        return@withContext TorrentDetails(
            infoHash = infoHash,
            name = name,
            size = infoByHeader["Размер"]?.ownText()?.substringBefore('(')?.trim()
                ?.takeIf(String::isNotEmpty),
            seeders = infoByHeader["Раздают"]?.ownText()?.trim()?.toUIntOrNull(),
            peers = infoByHeader["Качают"]?.ownText()?.trim()?.toUIntOrNull(),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = parseFullDate(infoByHeader["Добавлен"]?.ownText()),
            category = RutorCategoryMapper.detailsCategory(categoryUrl),
            uploader = infoByHeader["Залил"]?.text()?.trim()?.takeIf(String::isNotEmpty),
            lastCheckedEpochMillis = parseFullDate(infoByHeader["Сидер замечен"]?.ownText()),
            magnetUri = magnetUri,
            fileDownloadLink = urlPolicy.optionalUrl(
                pageUrl,
                document.selectFirst(FILE_ANCHOR)?.attr("href"),
            ),
            description = descriptionElement?.html(),
            posterUrl = posterUrl,
            screenshotUrls = screenshotUrls,
        )
    }

    /** 完整移除括号后缀并严格解析四位年 UTC 日期。 */
    private fun parseFullDate(
        /** header 对应原始日期，可为空。 */
        rawDate: String?,
    ): Long? {
        /** normalizedDate 是括号前完整日期时间。 */
        val normalizedDate = rawDate?.substringBefore('(')?.trim()
            ?.takeIf(FULL_DATE_PATTERN::matches)
            ?: return null
        return ProviderDateParser.parseUtcEpochMillis(normalizedDate, "dd-MM-yyyy H:m:s")
    }

    /** 从隐藏 textarea 中提取相对截图并使用详情页 base 解析。 */
    private fun extractScreenshots(
        /** 详情描述节点。 */
        descriptionElement: Element?,
        /** 已审核详情页 URL。 */
        pageUrl: String,
    ): List<String> {
        /** screenshotMarkup 是固定截图块 textarea 的解码文本。 */
        val screenshotMarkup = descriptionElement
            ?.select("div.hidewrap")
            /** wrapper 是当前隐藏内容块。 */
            ?.firstOrNull { wrapper ->
                wrapper.selectFirst("div.hidehead")?.ownText()?.trim() == "Скриншоты"
            }
            ?.selectFirst("textarea.hidearea")
            ?.wholeText()
            ?: return emptyList()
        /** screenshotDocument 使用真实页面作为相对链接基址。 */
        val screenshotDocument = Jsoup.parseBodyFragment(screenshotMarkup, pageUrl)
        return screenshotDocument.select("a > img")
            /** image 是当前截图候选。 */
            .mapNotNull { image -> urlPolicy.optionalUrl(pageUrl, image.attr("src")) }
            .distinct()
    }

    /** Rutor 固定详情 selector 与日期格式边界。 */
    private companion object {
        /** 详情标题。 */
        private const val NAME = "div#all > h1"
        /** 描述与媒体容器。 */
        private const val DESCRIPTION_CELL = "table#details > tbody > tr:nth-child(1) > td:nth-child(2)"
        /** 第一个下载区 anchor 是 magnet。 */
        private const val MAGNET_ANCHOR = "div#download > a:nth-child(1)"
        /** 第二个下载区 anchor 是 torrent 文件。 */
        private const val FILE_ANCHOR = "div#download > a:nth-child(2)"
        /** 日期必须完整匹配 dd-MM-yyyy H:m:s。 */
        private val FULL_DATE_PATTERN = Regex("^\\d{2}-\\d{2}-\\d{4} \\d{1,2}:\\d{1,2}:\\d{1,2}$")
    }
}
