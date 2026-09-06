package com.cla.clip.feature.torrent.search.provider.builtin.yts

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.TorrentFileSizeFormatter
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Yts 显式 key JSON parser；严格文档结构与逐电影、逐 torrent 容错分离。 */
internal class YtsJsonParser(
    /** 搜索结果与详情写入的稳定 Provider 身份。 */
    private val descriptor: ProviderDescriptor,
    /** JSON tree、字段校验与领域映射专用计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 解析列表响应并按电影、torrent 的输入顺序稳定展开。 */
    suspend fun parseResults(
        /** 受网络大小上限保护的 JSON 正文。 */
        response: String,
    ): List<Torrent> = withContext(parsingDispatcher) {
        /** root 必须是 JSON 对象。 */
        val root = parseObject(response)
        /** data 是固定列表协议容器。 */
        val data = root["data"] as? JsonObject ?: throw ProviderParseException()
        /** movieCount 必须是非负 JSON 整数。 */
        val movieCount = data.number("movie_count")?.takeIf { value -> value >= 0L }
            ?: throw ProviderParseException()
        /** movies 缺失只在明确零电影时合法。 */
        val moviesElement = data["movies"]
        if (moviesElement == null && movieCount == 0L) {
            return@withContext emptyList()
        }
        /** movies 存在时必须为数组，元素错误在逐项层处理。 */
        val movies = moviesElement as? JsonArray ?: throw ProviderParseException()
        /** movie 是当前候选，公共遍历保证取消不会被容错吞掉。 */
        val groupedResults = movies.mapNotNullCancellable { movie ->
            /** movieObject 非对象时只跳过当前候选。 */
            val movieObject = movie as? JsonObject ?: return@mapNotNullCancellable null
            parseMovie(movieObject)
        }
        groupedResults.flatten()
    }

    /** 解析详情响应并验证 movie ID 与目标 hash 双重身份。 */
    suspend fun parseDetails(
        /** 受限详情 JSON 正文。 */
        response: String,
        /** 页面 token 中经过验证的目标身份。 */
        expectedToken: YtsDetailsToken,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** root 非对象属于稳定顶层协议错误。 */
        val root = parseObject(response)
        /** data/movie 缺失表示当前详情不可用。 */
        val data = root["data"] as? JsonObject ?: return@withContext null
        /** movie 是当前详情对象。 */
        val movie = data["movie"] as? JsonObject ?: return@withContext null
        /** movieId 必须是正整数且与请求完全一致。 */
        val movieId = movie.number("id")?.takeIf { value -> value > 0L } ?: return@withContext null
        if (movieId != expectedToken.movieId) {
            return@withContext null
        }
        /** torrents 必须存在数组才能匹配目标下载身份。 */
        val torrents = movie["torrents"] as? JsonArray ?: return@withContext null
        /** matchedTorrent 在循环中保持首个合法 hash 匹配项。 */
        var matchedTorrent: JsonObject? = null
        /** element 是当前详情 torrent，逐个检查父协程取消。 */
        for (element in torrents) {
            currentCoroutineContext().ensureActive()
            /** candidate 非对象时继续尝试后续项。 */
            val candidate = element as? JsonObject ?: continue
            /** candidateHash 必须合法且精确匹配 token。 */
            val candidateHash = candidate.text("hash")?.let(MagnetInfoHashParser::normalize) ?: continue
            if (candidateHash == expectedToken.infoHash) {
                matchedTorrent = candidate
                break
            }
        }
        /** torrentObject 缺失表示响应未包含目标 hash。 */
        val torrentObject = matchedTorrent ?: return@withContext null
        /** movieTitle 是详情名称的必填组成。 */
        val movieTitle = movie.text("title_long")?.takeIf(String::isNotBlank) ?: return@withContext null
        /** screenshotUrls 仅保留通过 manifest host 策略的可选地址。 */
        val screenshotUrls = listOf(
            movie.text("medium_screenshot_image1"),
            movie.text("medium_screenshot_image2"),
            movie.text("medium_screenshot_image3"),
        ).mapNotNull(YtsUrlPolicy::optionalOutputUrl)
        TorrentDetails(
            infoHash = expectedToken.infoHash,
            name = torrentName(movieTitle, torrentObject),
            size = torrentObject.number("size_bytes")?.let(TorrentFileSizeFormatter::formatBytes),
            seeders = torrentObject.unsigned("seeds"),
            peers = torrentObject.unsigned("peers"),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = torrentObject.number("date_uploaded_unix")
                ?.let(ProviderDateParser::epochSecondsToMillis),
            category = Category.MOVIES,
            magnetUri = MagnetInfoHashParser.create(expectedToken.infoHash),
            fileDownloadLink = YtsUrlPolicy.optionalOutputUrl(torrentObject.text("url")),
            description = movie.text("description_full"),
            posterUrl = YtsUrlPolicy.optionalOutputUrl(movie.text("medium_cover_image")),
            screenshotUrls = screenshotUrls,
        )
    }

    /** 解析一部电影并映射其全部合法 torrent。 */
    private suspend fun parseMovie(
        /** 当前电影对象。 */
        movie: JsonObject,
    ): List<Torrent>? {
        /** movieId 必须是有界正整数。 */
        val movieId = movie.number("id")?.takeIf { value -> value > 0L } ?: return null
        /** movieTitle 必须是非空 JSON 字符串。 */
        val movieTitle = movie.text("title_long")?.takeIf(String::isNotBlank) ?: return null
        /** pageUrl 必须能构成精确 Yts 电影详情 token。 */
        val pageUrl = movie.text("url") ?: return null
        /** torrents 缺失或类型异常只使当前电影无效。 */
        val torrents = movie["torrents"] as? JsonArray ?: return null
        /** torrent 是当前下载候选，坏项不会阻断后续有效项。 */
        return torrents.mapNotNullCancellable { torrent ->
            /** torrentObject 非对象时只跳过自身。 */
            val torrentObject = torrent as? JsonObject ?: return@mapNotNullCancellable null
            /** infoHash 是列表稳定身份。 */
            val infoHash = torrentObject.text("hash")?.let(MagnetInfoHashParser::normalize)
                ?: return@mapNotNullCancellable null
            /** detailsToken 同时验证 pageUrl、movieId 与 hash。 */
            val detailsToken = YtsDetailsTokenParser.buildToken(
                pageUrl = pageUrl,
                movieId = movieId,
                infoHash = infoHash,
            ) ?: return@mapNotNullCancellable null
            Torrent(
                infoHash = infoHash,
                name = torrentName(movieTitle, torrentObject),
                size = torrentObject.number("size_bytes")?.let(TorrentFileSizeFormatter::formatBytes),
                seeders = torrentObject.unsigned("seeds"),
                peers = torrentObject.unsigned("peers"),
                providerId = descriptor.id,
                providerName = descriptor.displayName,
                uploadEpochMillis = torrentObject.number("date_uploaded_unix")
                    ?.let(ProviderDateParser::epochSecondsToMillis),
                category = Category.MOVIES,
                descriptionPageUrl = detailsToken,
            )
        }
    }

    /** 按固定上源规则拼接电影标题、清晰度、来源与编码。 */
    private fun torrentName(
        /** 非空电影展示标题。 */
        movieTitle: String,
        /** 当前 torrent 的可选展示字段。 */
        torrent: JsonObject,
    ): String {
        /** quality 缺失时使用固定短横线占位。 */
        val quality = torrent.text("quality") ?: "-"
        /** sourceType 缺失时使用固定短横线占位。 */
        val sourceType = torrent.text("type") ?: "-"
        /** codec 缺失时使用固定短横线占位。 */
        val codec = torrent.text("video_codec") ?: "-"
        return "$movieTitle [$quality] [$sourceType] [$codec]"
    }

    /** 将语法错误或非对象顶层转换为无正文稳定异常。 */
    private fun parseObject(
        /** 未信任 JSON 正文。 */
        response: String,
    ): JsonObject =
        (runCatching { Json.parseToJsonElement(response) }.getOrNull() as? JsonObject)
            ?: throw ProviderParseException()

    /** 字符串字段不接受数字或布尔值的隐式转换。 */
    private fun JsonObject.text(
        /** 固定上源字段名。 */
        key: String,
    ): String? = (get(key) as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content

    /** 整数字段只接受 JSON number 且以 Long 拒绝溢出。 */
    private fun JsonObject.number(
        /** 固定上源字段名。 */
        key: String,
    ): Long? = (get(key) as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.longOrNull

    /** 人数只接受 UInt 范围内的 JSON 整数。 */
    private fun JsonObject.unsigned(
        /** 固定上源字段名。 */
        key: String,
    ): UInt? = number(key)
        ?.takeIf {
            /** value 是待校验的外部人数整数。 */
            value -> value in 0L..UInt.MAX_VALUE.toLong()
        }
        ?.toUInt()
}
