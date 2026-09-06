package com.cla.clip.feature.torrent.search.provider.builtin.eztv

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
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** Eztv 固定 HTML 解析；只做内存计算，可选坏链接降级为空且不影响合法磁力。 */
internal class EztvHtmlParser(
    /** 来源稳定身份由 Provider 注入。 */
    private val descriptor: ProviderDescriptor,
    /** 与网络入口共用的精确 HTTPS 策略。 */
    private val urlPolicy: BuiltinProviderUrlPolicy,
    /** DOM 与领域映射必须在计算调度器运行。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 搜索保留固定上源最后 table 与丢弃前两行的规则，零匹配直接为空。 */
    suspend fun parseResults(
        /** 不可信 HTML，仅解析不执行。 */
        html: String,
        /** 已验证同站的相对地址基址。 */
        pageUrl: String,
    ): List<Torrent> = withContext(parsingDispatcher) {
        /** row 是跳过两行后的单个上源候选。 */
        Jsoup.parse(html, pageUrl).select("table:last-of-type > tbody > tr").drop(2).mapNotNullCancellable { row ->
            /** epinfo 自身文本为标题，空名称不能展示。 */
            val titleLink = row.selectFirst("td:nth-child(2) > a.epinfo") ?: return@mapNotNullCancellable null
            /** 名称保持上源 ownText 语义。 */
            val name = titleLink.ownText().takeIf(String::isNotBlank) ?: return@mapNotNullCancellable null
            /** magnet 原文保留，公共 parser 验证 btih 身份。 */
            val magnet = row.selectFirst("td:nth-child(3) > a.magnet")?.attr("href") ?: return@mapNotNullCancellable null
            /** 非法 hash 只跳过当前候选。 */
            val hash = MagnetInfoHashParser.parse(magnet) ?: return@mapNotNullCancellable null
            Torrent(
                infoHash = hash, name = name, providerId = descriptor.id, providerName = descriptor.displayName,
                size = row.selectFirst("td:nth-child(4)")?.ownText()?.takeIf(String::isNotBlank),
                seeders = row.selectFirst("td:nth-child(6)")?.ownText()?.toUIntOrNull(), peers = 0u,
                category = Category.SERIES, magnetUri = magnet,
                descriptionPageUrl = optionalUrl(pageUrl, titleLink.attr("href")),
                fileDownloadLink = optionalUrl(pageUrl, row.selectFirst("td:nth-child(3) > a:nth-child(2)")?.attr("href")),
            )
        }
    }

    /** 详情缺标题或合法 magnet 返回 null；其他字段无效时保留可用详情。 */
    suspend fun parseDetails(
        /** 有大小上限的原始 HTML。 */
        html: String,
        /** 网络入口已经校验的地址。 */
        pageUrl: String,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** DOM 局部持有，不进入日志或展示执行环境。 */
        val document = Jsoup.parse(html, pageUrl)
        /** 固定上源标题 selector，禁止宽泛页面标题替代。 */
        val name = document.selectFirst("#header_holder > table > tbody > tr:nth-child(1) > td > h1 > span")
            ?.ownText()?.takeIf(String::isNotBlank) ?: return@withContext null
        /** magnet 按原始链接保留，不丢站点 tracker。 */
        val magnet = document.selectFirst("a[title=Magnet Link]")?.attr("href") ?: return@withContext null
        /** 必填身份用公共规则验证。 */
        val hash = MagnetInfoHashParser.parse(magnet) ?: return@withContext null
        /** 固定布局中三列的共同前缀只用于 selector 组合。 */
        val columns = "#header_holder > table > tbody > tr:nth-child(2) > td > table.episode_columns_holder > tbody > tr:nth-child(1)"
        /** 第三列第一信息单元格包含 Filesize/Released 标签。 */
        val info = document.selectFirst("$columns > td:nth-child(3) > table > tbody > tr:nth-child(2) > td > table > tbody > tr:nth-child(1) > td")
        /** 日期仅去数字后上源序数，不对其他污染做宽泛清洗。 */
        val date = textAfterLabel(info, "Released:")?.replace(Regex("(\\d+)(st|nd|rd|th)"), "$1")
        /** 中列统计表维持上源红色做种、绿色 peers 对应关系。 */
        val statistics = "$columns > td.episode_middle_column > table:nth-child(2) > tbody > tr:nth-child(2) > td > div > table > tbody > tr > td:nth-child(2)"
        TorrentDetails(
            infoHash = hash, name = name, providerId = descriptor.id, providerName = descriptor.displayName,
            magnetUri = magnet, category = Category.SERIES, size = textAfterLabel(info, "Filesize:"),
            uploadEpochMillis = date?.let { ProviderDateParser.parseUtcEpochMillis(it, "d MMM yyyy") },
            seeders = document.selectFirst("$statistics > span.stat_red")?.ownText()?.toUIntOrNull(),
            peers = document.selectFirst("$statistics > span.stat_green")?.ownText()?.toUIntOrNull(),
            fileDownloadLink = optionalUrl(pageUrl, document.selectFirst("a[title=Download Torrent]")?.attr("href")),
            description = document.selectFirst("div.desc_big")?.html(),
            posterUrl = optionalUrl(pageUrl, document.selectFirst("$columns > td.episode_left_column > table > tbody > tr:nth-child(2) > td > a:nth-child(1) > img")?.attr("src")),
        )
    }

    /** 只接受标签紧随的 TextNode；嵌套元素不能冒充上源标量字段。 */
    private fun textAfterLabel(
        /** 固定详情信息容器，缺失即无可选字段。 */
        container: Element?,
        /** 固定上源标签，不来自用户输入。 */
        label: String,
    ): String? {
        /** element 为容器内候选标签；自身文字精确匹配避免读取其他区块。 */
        val node = container?.getAllElements()?.firstOrNull { element -> element.ownText() == label }?.nextSibling()
        return (node as? TextNode)?.text()?.trim()?.takeIf(String::isNotBlank)
    }

    /** 可选外部链接先按公共策略校验；安全拒绝只清空该字段。 */
    private fun optionalUrl(
        /** 已验证的同站 HTTPS 基址，只用于解析相对链接。 */
        baseUrl: String,
        /** 未信任的可选链接，缺失或不安全时降级为空。 */
        candidate: String?,
    ): String? = try {
        urlPolicy.resolveOptionalHttpsUrl(baseUrl, candidate)
    /** failure 仅为 URL 策略拒绝，禁止输出地址或异常正文。 */
    } catch (failure: HttpDeniedException) { null }
}
