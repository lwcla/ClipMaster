package com.cla.clip.feature.torrent.search.provider.builtin.torrentkitty

import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** TorrentKitty 详情页独立 DOM parser，使用页面 base 解析相对文件。 */
internal class TorrentKittyDetailsParser(
    /** Provider 稳定身份来源。 */
    private val descriptor: ProviderDescriptor,
    /** 同站选填 URL 策略。 */
    private val urlPolicy: TorrentKittyUrlPolicy,
    /** DOM 与领域映射计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 从固定摘要行与 action 顺序解析详情。 */
    suspend fun parse(
        /** 有共享大小上限的 HTML。 */
        html: String,
        /** 已审核详情页 URL。 */
        pageUrl: String,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** document 使用真实页面作为相对链接 base。 */
        val document = Jsoup.parse(html, pageUrl)
        /** name 必须来自非空 h2。 */
        val name = document.selectFirst(NAME)?.ownText()?.trim()?.takeIf(String::isNotEmpty)
            ?: return@withContext null
        /** magnetUri 固定为 action 第二 anchor。 */
        val magnetUri = document.selectFirst(MAGNET_ANCHOR)?.attr("href")
            ?: return@withContext null
        /** infoHash 使用公共规则规范化并校验。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return@withContext null
        /** rawDate 是摘要第五行固定日期。 */
        val rawDate = document.selectFirst(DATE_CELL)?.ownText()?.trim()
        /** normalizedDate 只接受完整固定宽度。 */
        val normalizedDate = rawDate?.takeIf(DATE_PATTERN::matches)
        return@withContext TorrentDetails(
            infoHash = infoHash,
            name = name,
            size = document.selectFirst(SIZE_CELL)?.ownText()?.trim()?.takeIf(String::isNotEmpty)
                ?.uppercase(Locale.ROOT),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = normalizedDate?.let {
                /** date 是已通过宽度校验的详情日期。 */
                date ->
                ProviderDateParser.parseUtcEpochMillis(date, "yyyy-MM-dd")
            },
            magnetUri = magnetUri,
            fileDownloadLink = urlPolicy.optionalUrl(
                pageUrl,
                document.selectFirst(FILE_ANCHOR)?.attr("href"),
            ),
        )
    }

    /** TorrentKitty 固定详情 selector。 */
    private companion object {
        /** 详情标题。 */
        private const val NAME = "h2"
        /** 摘要第四行是大小。 */
        private const val SIZE_CELL = "table.detailSummary > tbody > tr:nth-child(4) > td"
        /** 摘要第五行是日期。 */
        private const val DATE_CELL = "table.detailSummary > tbody > tr:nth-child(5) > td"
        /** action 第二 anchor 是 magnet。 */
        private const val MAGNET_ANCHOR = "p.action > a:nth-child(2)"
        /** action 第一 anchor 是 torrent 文件。 */
        private const val FILE_ANCHOR = "p.action > a:nth-child(1)"
        /** 日期必须完整匹配 yyyy-MM-dd。 */
        private val DATE_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    }
}
