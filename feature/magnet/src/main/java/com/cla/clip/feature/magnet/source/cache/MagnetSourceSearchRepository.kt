package com.cla.clip.feature.magnet.source.cache

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.cla.clip.feature.magnet.MAGNET_SOURCE_ACADEMIC_TORRENTS
import com.cla.clip.feature.magnet.MagnetTextNormalizer
import com.cla.clip.feature.magnet.MagnetUriBuilder
import com.cla.clip.base.general.utils.logD
import javax.inject.Inject
import javax.inject.Singleton

/** 磁力源索引搜索仓库，读取 cacheDir 中的 Academic Torrents 缓存库。 */
@Singleton
class MagnetSourceSearchRepository @Inject constructor(
    private val dao: MagnetSourceCacheDao,
    private val syncCoordinator: MagnetSourceSyncCoordinator,
) {
    companion object {
        private const val TAG = "MagnetSourceSearchRepository"
        const val MIN_SEARCH_QUERY_LENGTH = 2
    }

    /** 获取缓存状态。 */
    suspend fun getCacheState(): MagnetSourceCacheState {
        return syncCoordinator.getCacheState()
    }

    /** 同步源索引。 */
    suspend fun sync(force: Boolean = false): MagnetSourceCacheState {
        return syncCoordinator.sync(force)
    }

    /** 同步低敏进度。 */
    val progress = syncCoordinator.progress

    /** 按关键词分页搜索；关键词过短时返回空 PagingSource。 */
    fun search(query: String): PagingSource<Int, MagnetSearchResult> {
        val normalizedQuery = MagnetTextNormalizer.normalizeDisplayQuery(query)
        if (normalizedQuery.length < MIN_SEARCH_QUERY_LENGTH) {
            return EmptyMagnetPagingSource()
        }
        val ftsQuery = MagnetSourceSearchQueryFormatter.buildFtsQuery(normalizedQuery)
        val likeKeyword = MagnetTextNormalizer.escapeForLike(normalizedQuery)
        val source = if (ftsQuery.isBlank()) {
            logD(TAG) { "磁力搜索降级 LIKE reasonCode=fts_empty queryLength=${normalizedQuery.length}" }
            dao.searchLike(
                sourceId = MAGNET_SOURCE_ACADEMIC_TORRENTS,
                exactQuery = normalizedQuery,
                likeKeyword = likeKeyword,
                rankKeyword = normalizedQuery
            )
        } else {
            dao.search(
                sourceId = MAGNET_SOURCE_ACADEMIC_TORRENTS,
                ftsQuery = ftsQuery,
                exactQuery = normalizedQuery,
                likeKeyword = likeKeyword,
                rankKeyword = normalizedQuery
            )
        }
        return MagnetSearchResultPagingSource(source)
    }
}

private class EmptyMagnetPagingSource : PagingSource<Int, MagnetSearchResult>() {
    override fun getRefreshKey(state: PagingState<Int, MagnetSearchResult>): Int? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MagnetSearchResult> {
        return LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
    }
}

private class MagnetSearchResultPagingSource(
    private val delegate: PagingSource<Int, MagnetSourceSearchRow>,
) : PagingSource<Int, MagnetSearchResult>() {
    init {
        delegate.registerInvalidatedCallback { invalidate() }
    }

    override fun getRefreshKey(state: PagingState<Int, MagnetSearchResult>): Int? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MagnetSearchResult> {
        return when (val result = delegate.load(params)) {
            is LoadResult.Page -> LoadResult.Page(
                data = result.data.mapNotNull { row -> row.toResult() },
                prevKey = result.prevKey,
                nextKey = result.nextKey,
                itemsBefore = result.itemsBefore,
                itemsAfter = result.itemsAfter
            )
            is LoadResult.Error -> LoadResult.Error(result.throwable)
            is LoadResult.Invalid -> LoadResult.Invalid()
        }
    }

    private fun MagnetSourceSearchRow.toResult(): MagnetSearchResult? {
        val magnetUri = MagnetUriBuilder.build(infoHash, title) ?: return null
        return MagnetSearchResult(
            id = id,
            sourceId = sourceId,
            infoHash = infoHash,
            title = title,
            detailUrl = detailUrl,
            sizeBytes = sizeBytes,
            category = category,
            description = description,
            magnetUri = magnetUri
        )
    }
}
