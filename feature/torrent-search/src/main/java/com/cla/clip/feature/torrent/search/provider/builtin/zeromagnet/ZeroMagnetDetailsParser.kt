package com.cla.clip.feature.torrent.search.provider.builtin.zeromagnet

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.normalizeProviderDisplaySize
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** 0Magnet 独立详情 DOM parser。 */
internal class ZeroMagnetDetailsParser(
    /** Provider 元数据。 */
    private val descriptor: ProviderDescriptor,
    /** 计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 名称和合法 magnet 必填，坏大小或日期独立降级。 */
    suspend fun parse(
        /** 有上限 HTML。 */
        html: String,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** document 只静态解析。 */
        val document = Jsoup.parse(html)
        /** name 拒绝空标题。 */
        val name = document.selectFirst("h2.magnet-title")?.ownText()?.trim()?.takeIf(String::isNotEmpty) ?: return@withContext null
        /** magnetUri 从固定 input value 读取。 */
        val magnetUri = document.selectFirst("input#input-magnet")?.attr("value") ?: return@withContext null
        /** infoHash 由公共 parser 严格验证。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return@withContext null
        TorrentDetails(
            infoHash = infoHash, name = name,
            size = document.selectFirst("dl.torrent-info > dd:nth-child(4)")?.ownText()?.let(::normalizeProviderDisplaySize),
            providerId = descriptor.id, providerName = descriptor.displayName,
            uploadEpochMillis = document.selectFirst("dl.torrent-info > dd:nth-child(6)")?.ownText()?.let {
                /** rawDate 是固定 UTC 日期文本。 */
                rawDate -> ProviderDateParser.parseUtcEpochMillis(rawDate, "yyyy-MM-dd HH:mm:ss")
            },
            category = Category.PORN, magnetUri = magnetUri,
        )
    }
}
