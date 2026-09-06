package com.cla.clip.feature.torrent.search.provider.builtin.nekobt

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** NekoBT 详情页无网络 parser。 */
internal class NekoBtDetailsParser(
    /** Provider 稳定身份。 */ private val descriptor: ProviderDescriptor,
    /** 同站可选 URL 策略。 */ private val urlPolicy: BuiltinProviderUrlPolicy,
    /** DOM 与领域映射调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 名称与合法 magnet/btih 必填，其余字段独立降级。 */
    suspend fun parse(/** 有大小上限的 HTML。 */ html: String, /** 已校验页面 URL。 */ pageUrl: String): TorrentDetails? =
        withContext(parsingDispatcher) {
            /** document 只做静态 DOM 解析。 */ val document = Jsoup.parse(html, pageUrl)
            /** card 是固定上源信息区域。 */ val card = document.selectFirst("div.grid > div:nth-child(1) > div.card:nth-child(1) > div.card-body") ?: return@withContext null
            /** name 精确读取嵌套首个 span。 */ val name = card.selectFirst("h2.card-title > div > span > span:nth-child(1)")?.ownText()?.trim()?.takeIf(String::isNotEmpty) ?: return@withContext null
            /** magnetUri 是详情必填身份。 */ val magnetUri = card.selectFirst("""a[href^="magnet:?"]""")?.attr("href") ?: return@withContext null
            /** infoHash 必须可规范化。 */ val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return@withContext null
            TorrentDetails(
                infoHash = infoHash,
                name = name,
                size = card.selectFirst("""div:nth-child(2) > span[data-tip="Total Size"]""")?.ownText()?.trim(),
                seeders = card.selectFirst("""div:nth-child(2) > span[data-tip="Seeders"]""")?.ownText()?.trim()?.toUIntOrNull(),
                peers = card.selectFirst("""div:nth-child(2) > span[data-tip="Leechers"]""")?.ownText()?.trim()?.toUIntOrNull(),
                providerId = descriptor.id,
                providerName = descriptor.displayName,
                /** rawDate 是详情页固定 UTC 日期文本。 */
                uploadEpochMillis = card.selectFirst("div:nth-child(2) span:nth-child(8)")?.ownText()?.trim()?.let { rawDate -> ProviderDateParser.parseUtcEpochMillis(rawDate, "yyyy-MM-dd HH:mm:ss") },
                category = Category.ANIME,
                uploader = card.selectFirst("""div:nth-child(2) > span[data-tip="Uploader"] > a""")?.ownText()?.trim()?.takeIf(String::isNotEmpty),
                magnetUri = magnetUri,
                fileDownloadLink = optionalUrl(pageUrl, card.selectFirst("""a[href^="/api/v1/torrents"]""")?.attr("href")),
                description = document.selectFirst("div.grid > div:nth-child(1) > div.card:nth-last-child(3) div.markdown")?.html(),
                posterUrl = optionalUrl(pageUrl, document.selectFirst("""img[alt^="Banner for "]""")?.attr("src")),
            )
        }

    /** 安全策略拒绝的可选链接只降级对应字段。 */
    private fun optionalUrl(/** 可信基址。 */ baseUrl: String, /** 未信任候选。 */ candidate: String?): String? =
        runCatching { urlPolicy.resolveOptionalHttpsUrl(baseUrl, candidate) }.getOrNull()
}
