package com.cla.clip.feature.torrent.search.provider.builtin.filemood

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import okhttp3.HttpUrl.Companion.toHttpUrl

/** FileMood 固定表格解析，无网络依赖；缺必填行跳过，缺必填详情返回 null。 */
internal class FileMoodHtmlParser(
    /** 来源稳定字段由入口提供。 */
    private val descriptor: ProviderDescriptor,
    /** 必填详情 URL 与输入入口使用同站 HTTPS 策略。 */
    private val urlPolicy: BuiltinProviderUrlPolicy,
    /** 所有 DOM 与映射的计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 保留 btn-success 行 selector，空集合即合法零结果。 */
    suspend fun parseResults(
        /** 受限原始 HTML，不执行描述脚本。 */
        html: String,
        /** 同站相对地址基址。 */
        pageUrl: String,
    ): List<Torrent> = withContext(parsingDispatcher) {
        /** row 是当前上源候选，协作遍历让取消及时生效。 */
        Jsoup.parse(html, pageUrl).select("table > tbody > tr:has(a.btn-success)").mapNotNullCancellable { row ->
            /** 名称按固定上源完整文本读取。 */
            val name = row.selectFirst("> td.dn-title")?.text()?.takeIf(String::isNotBlank) ?: return@mapNotNullCancellable null
            /** 外部必填链接安全拒绝只丢当前行。 */
            val url = try {
                urlPolicy.resolveOptionalHttpsUrl(pageUrl, row.selectFirst("td.dn-btn > div > a")?.attr("href"))
            /** failure 是精确 host 或 scheme 拒绝，不带原文。 */
            } catch (failure: HttpDeniedException) { null } ?: return@mapNotNullCancellable null
            /** 身份只能来自最后一个路径文件名，query/fragment 不参与身份并直接拒绝。 */
            val parsedUrl = url.toHttpUrl()
            if (parsedUrl.query != null || parsedUrl.fragment != null) return@mapNotNullCancellable null
            /** 上源详情必须有 .html 后缀与分隔标题的连字符。 */
            val filename = parsedUrl.pathSegments.lastOrNull() ?: return@mapNotNullCancellable null
            if (!filename.endsWith(".html") || '-' !in filename) return@mapNotNullCancellable null
            /** hash 来自 .html 前最后一个连字符之后，仍须通过公共规范化。 */
            val hash = MagnetInfoHashParser.normalize(filename.removeSuffix(".html").substringAfterLast('-'))
                ?: return@mapNotNullCancellable null
            /** 安全下标替代上游两元素解构，零/一/多字段都不会崩溃。 */
            val counts = row.selectFirst("td.dn-status")?.text()?.split('/').orEmpty()
            Torrent(
                infoHash = hash, name = name, providerId = descriptor.id, providerName = descriptor.displayName,
                size = row.selectFirst("td.dn-size")?.text()?.takeIf(String::isNotBlank),
                seeders = counts.getOrNull(0)?.trim()?.toUIntOrNull(), peers = counts.getOrNull(1)?.trim()?.toUIntOrNull(),
                category = Category.OTHER, descriptionPageUrl = url,
            )
        }
    }

    /** 详情 hash 和名称必填，日期仅取首个空白之前的 UTC 日期。 */
    suspend fun parseDetails(
        /** 有上限 HTML，只在内存解析。 */
        html: String,
        /** 上游详情没有链接产出，基址只供 DOM 一致上下文。 */
        pageUrl: String,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** 本次调用私有 DOM，不缓存用户内容。 */
        val document = Jsoup.parse(html, pageUrl)
        /** 固定上源第一张表的名称。 */
        val name = document.selectFirst("div.well > table:nth-child(1) > tbody > tr:nth-child(1) > td > h1 > b")
            ?.ownText()?.takeIf(String::isNotBlank) ?: return@withContext null
        /** 第三张表固定字段前缀。 */
        val table = "div.well > table:nth-child(3) > tbody"
        /** 必填 hash 支持公共 hex/base32 规范化。 */
        val hash = document.selectFirst("$table > tr:nth-child(5) > td:nth-child(2) > p")?.ownText()
            ?.let(MagnetInfoHashParser::normalize) ?: return@withContext null
        /** character 是日期后的第一个空白边界，时间部分按上源不参与解析。 */
        val date = document.selectFirst("$table > tr:nth-child(4) > td:nth-child(2) > p > b")?.ownText()
            // character 是日期文本字符，首空白后忽略上源时间部分。
            ?.takeWhile { character -> !character.isWhitespace() }
        TorrentDetails(
            infoHash = hash, name = name, providerId = descriptor.id, providerName = descriptor.displayName,
            magnetUri = MagnetInfoHashParser.create(hash), category = Category.OTHER,
            size = document.selectFirst("$table > tr:nth-child(2) > td:nth-child(2) > p > b")?.ownText()?.takeIf(String::isNotBlank),
            lastCheckedEpochMillis = date?.let { ProviderDateParser.parseUtcEpochMillis(it, "yyyy-MM-dd") },
        )
    }
}
