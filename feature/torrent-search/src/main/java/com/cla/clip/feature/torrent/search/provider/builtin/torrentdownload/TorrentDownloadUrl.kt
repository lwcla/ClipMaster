package com.cla.clip.feature.torrent.search.provider.builtin.torrentdownload

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl

/** TorrentDownload 搜索、最新与热门 URL 构造器。 */
internal object TorrentDownloadUrlBuilder {
    /** manifest 审计通过的固定主站根地址。 */
    const val BASE_URL = "https://torrentdownload.info"

    /** 搜索分类按固定上游忽略，查询只作为结构化 query 参数。 */
    fun searchUrl(
        /** 原始搜索词。 */ rawQuery: String,
    ): String {
        /** query 删除控制字符并裁剪边界空白。 */
        val query = sanitizeProviderQuery(rawQuery)
        return BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", query)
            .build()
            .toString()
    }

    /** 构造固定最新页。 */
    fun latestUrl(): String = BASE_URL.toHttpUrl().newBuilder().addPathSegment("latest").build().toString()

    /** 构造固定热门页。 */
    fun topUrl(): String = BASE_URL.toHttpUrl().newBuilder().addPathSegment("top").build().toString()
}

/** TorrentDownload 列表与详情分类文本映射。 */
internal object TorrentDownloadCategoryMapper {
    /** 删除非 ASCII 字母后按固定上游词表归一化，未知值归 OTHER。 */
    fun fromPageText(
        /** 页面分类展示文本。 */ rawCategory: String,
    ): Category {
        /** normalizedCategory 用于兼容空格、斜杠和标点分隔。 */
        val normalizedCategory = rawCategory.replace(Regex("[^A-Za-z]+"), "")
        return when (normalizedCategory) {
            "XXX", "XXXVideo", "XXXHDVideo", "XXXPictures", "Adult",
            "AdultPornHDVideo", "AdultPornPictures", "AdultPornVideo" -> Category.PORN
            "Anime", "AnimeEnglishtranslated", "AnimeAnimeOther" -> Category.ANIME
            "Applications", "ApplicationsAndroid", "ApplicationsWindows", "Software" -> Category.APPS
            "BooksAcademic", "BooksComics", "BooksEbooks", "BooksEducational", "BooksMagazines",
            "BooksFiction", "BooksNonfiction", "BooksTextbooks", "Ebooks", "OtherEbooks",
            "OtherComics", "AudioBooks", "AudioAudiobooks" -> Category.BOOKS
            "Games", "GamesWindows" -> Category.GAMES
            "Movies", "MoviesAction", "MoviesConcerts", "MoviesCrime", "MoviesDocumentary",
            "MoviesDubbedMovies", "MoviesHighresMovies", "MoviesMusicvideos", "MoviesThriller",
            "VideoMovies" -> Category.MOVIES
            "Music", "MusicHardrock", "MusicMp", "MusicFLAC", "MusicLossless", "MusicRB",
            "MusicTranceHouseDance", "VideoMusic", "AudioMusic" -> Category.MUSIC
            "TV", "TVBBC", "TVshows", "Television" -> Category.SERIES
            else -> Category.OTHER
        }
    }
}
