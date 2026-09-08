package com.cla.clip.feature.torrent.search.provider.builtin.subsplease

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup

/** 已校验详情页与页面元数据，供 show API 精确匹配。 */
internal data class SubsPleasePageDraft(
    /** 页面 sid 属性。 */ val showId: String,
    /** 非空 show 标题。 */ val showName: String,
    /** 详情 URL 中的剧集编号。 */ val episode: String,
    /** 详情 URL 中的分辨率。 */ val resolution: String,
    /** 可选纯文本描述。 */ val description: String?,
    /** 可选同站海报 URL。 */ val posterUrl: String?,
)

/** SubsPlease 详情 HTML 与 show JSON parser；不持有网络能力。 */
internal class SubsPleaseDetailsParser(
    /** 详情稳定 Provider 身份。 */ private val descriptor: ProviderDescriptor,
    /** 海报和 torrent 文件使用的同站 URL 策略。 */ private val urlPolicy: BuiltinProviderUrlPolicy,
    /** DOM、JSON 与领域映射计算调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 从静态详情 HTML 提取 show ID 和展示元数据。 */
    suspend fun parsePage(
        /** 受限 HTML，只静态解析不执行。 */ html: String,
        /** 已验证 URL 与剧集参数。 */ request: SubsPleaseDetailsRequest,
    ): SubsPleasePageDraft? = withContext(parsingDispatcher) {
        /** document 仅用于本次 DOM 提取。 */
        val document = Jsoup.parse(html, request.pageUrl)
        /** showId 是二次 API 的必填 sid。 */
        val showId = document.selectFirst("table#show-release-table")?.attr("sid")
            ?.trim()?.takeIf(String::isNotEmpty) ?: return@withContext null
        /** showName 是最终详情标题的必填部分。 */
        val showName = document.selectFirst("h1.entry-title")?.ownText()
            ?.trim()?.takeIf(String::isNotEmpty) ?: return@withContext null
        /** posterCandidate 是不可信可选地址，跨站时独立降级。 */
        val posterCandidate = document.selectFirst("img.img-responsive.img-center")?.attr("src")
        /** posterUrl 只有通过同站 policy 才进入领域模型。 */
        val posterUrl = runCatching { urlPolicy.resolveOptionalHttpsUrl(request.pageUrl, posterCandidate) }.getOrNull()
        SubsPleasePageDraft(
            showId = showId, showName = showName, episode = request.episode,
            resolution = request.resolution,
            description = document.selectFirst("div.series-syn > p")?.ownText()?.trim()?.takeIf(String::isNotEmpty),
            posterUrl = posterUrl,
        )
    }

    /** 从 show JSON 精确选择 episode/resolution 并映射详情。 */
    suspend fun parseShow(
        /** 受 5 MiB 上限保护的 show JSON。 */ responseJson: String,
        /** HTML 阶段已验证的详情草稿。 */ draft: SubsPleasePageDraft,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** root 语法错误或非对象是稳定解析失败。 */
        val root = runCatching { Json.parseToJsonElement(responseJson) }.getOrNull() as? JsonObject
            ?: throw ProviderParseException()
        /** episodes 缺失表示当前 show 无可用详情。 */
        val episodes = root["episode"] as? JsonObject ?: return@withContext null
        /** episodeObject 按字段值而非对象 key 精确匹配。 */
        val episodeObject = episodes.values.firstNotNullOfOrNull { rawEpisode ->
            currentCoroutineContext().ensureActive()
            /** candidate 必须是对象且 episode 完全相等。 */
            (rawEpisode as? JsonObject)?.takeIf { candidate -> candidate.string("episode") == draft.episode }
        } ?: return@withContext null
        /** downloads 必须是数组。 */
        val downloads = episodeObject["downloads"] as? JsonArray ?: return@withContext null
        /** downloadObject 按 resolution 完全相等。 */
        val downloadObject = downloads.firstNotNullOfOrNull { rawDownload ->
            currentCoroutineContext().ensureActive()
            /** candidate 非对象或分辨率不匹配时继续查找。 */
            (rawDownload as? JsonObject)?.takeIf { candidate -> candidate.string("res") == draft.resolution }
        } ?: return@withContext null
        /** magnetUri 和合法 btih 是详情必填身份。 */
        val magnetUri = downloadObject.string("magnet") ?: return@withContext null
        /** infoHash 统一规范化为小写 hex。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return@withContext null
        /** torrentCandidate 是可选同站下载地址。 */
        val torrentCandidate = downloadObject.string("torrent")
        /** fileDownloadLink 跨站或非法时独立降级。 */
        val fileDownloadLink = runCatching {
            urlPolicy.resolveOptionalHttpsUrl(SubsPleaseUrlBuilder.BASE_URL, torrentCandidate)
        }.getOrNull()
        TorrentDetails(
            infoHash = infoHash, name = "${draft.showName} - ${draft.episode} [${draft.resolution}p]",
            size = SubsPleaseMagnetSizeParser.parseFormattedSize(magnetUri),
            providerId = descriptor.id, providerName = descriptor.displayName,
            uploadEpochMillis = episodeObject.string("release_date")?.let(::parseEpisodeDate),
            category = Category.ANIME, magnetUri = magnetUri,
            fileDownloadLink = fileDownloadLink, description = draft.description, posterUrl = draft.posterUrl,
        )
    }

    /** 从上源复合日期中提取独立 `dd MMM yyyy` 并严格按 UTC 解析。 */
    private fun parseEpisodeDate(
        /** show API 的日期展示文本。 */ rawDate: String,
    ): Long? {
        /** match 只接受英文三字母月份和四位年份片段。 */
        val match = EPISODE_DATE_PATTERN.find(rawDate) ?: return null
        return ProviderDateParser.parseUtcEpochMillis(match.value, "dd MMM yyyy")
    }

    /** 字段只接受非空 JSON 字符串。 */
    private fun JsonObject.string(
        /** 固定 show API 字段名。 */ key: String,
    ): String? = (get(key) as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    /** SubsPlease 日期形状常量。 */
    private companion object {
        /** 只提取独立日、英文短月份与四位年。 */
        private val EPISODE_DATE_PATTERN = Regex("\\b\\d{1,2} [A-Za-z]{3} \\d{4}\\b")
    }
}
