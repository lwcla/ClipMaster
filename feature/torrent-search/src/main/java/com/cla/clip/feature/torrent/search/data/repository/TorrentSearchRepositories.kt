package com.cla.clip.feature.torrent.search.data.repository

import com.cla.clip.feature.torrent.search.data.local.BookmarkedTorrentDao
import com.cla.clip.feature.torrent.search.data.local.BookmarkedTorrentEntity
import com.cla.clip.feature.torrent.search.data.local.TorrentSearchHistoryDao
import com.cla.clip.feature.torrent.search.data.local.TorrentSearchHistoryEntity
import com.cla.clip.feature.torrent.search.data.local.ViewedTorrentDao
import com.cla.clip.feature.torrent.search.data.local.ViewedTorrentEntity
import com.cla.clip.feature.torrent.search.data.model.BookmarkedTorrent
import com.cla.clip.feature.torrent.search.data.model.TorrentSearchHistory
import com.cla.clip.feature.torrent.search.data.model.ViewedTorrent
import com.cla.clip.feature.torrent.search.input.TorrentSearchInputNormalizer
import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.provider.ProviderId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

/** 书签 Repository 契约；调用方不接触 Room 实体。 */
internal interface BookmarkRepository {
    /** 观察最近书签领域模型。 */
    fun observeBookmarks(): Flow<List<BookmarkedTorrent>>

    /** 精确观察合法 infoHash 的书签存在状态；非法身份返回固定 false 流。 */
    fun observeBookmarked(infoHash: String): Flow<Boolean>

    /** 校验并保存书签；非法稳定身份返回 false 且不写数据库。 */
    suspend fun save(bookmark: BookmarkedTorrent): Boolean

    /** 删除合法 infoHash 对应书签并返回是否命中。 */
    suspend fun delete(infoHash: String): Boolean

    /** 清空书签并返回删除数量。 */
    suspend fun clear(): Int
}

/** 搜索历史 Repository 契约；统一入口清洗、去重与数量上限。 */
internal interface TorrentSearchHistoryRepository {
    /** 观察最近搜索历史。 */
    fun observeHistory(): Flow<List<TorrentSearchHistory>>

    /** 保存安全且未超限的查询；拒绝时返回 false。 */
    suspend fun saveQuery(query: String): Boolean

    /** 按任意写法规范化后删除历史。 */
    suspend fun deleteQuery(query: String): Boolean

    /** 清空搜索历史并返回删除数量。 */
    suspend fun clear(): Int
}

/** 已查看 Repository 契约；infoHash 是唯一身份。 */
internal interface ViewedTorrentRepository {
    /** 观察最近已查看记录。 */
    fun observeViewed(): Flow<List<ViewedTorrent>>

    /** 校验并刷新查看时间；非法 hash 返回 false。 */
    suspend fun markViewed(infoHash: String): Boolean

    /** 清空已查看记录并返回删除数量。 */
    suspend fun clear(): Int
}

/** 仅供既有 JVM 构造调用兼容的空书签仓库；Hilt 生产始终注入 Room 实现。 */
internal object NoOpBookmarkRepository : BookmarkRepository {
    /** 空实现不产生整表记录。 */
    override fun observeBookmarks(): Flow<List<BookmarkedTorrent>> = kotlinx.coroutines.flow.flowOf(emptyList())
    /** 空实现始终报告未收藏。 */
    override fun observeBookmarked(infoHash: String): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)
    /** 空实现不保存数据。 */
    override suspend fun save(bookmark: BookmarkedTorrent): Boolean = false
    /** 空实现不删除数据。 */
    override suspend fun delete(infoHash: String): Boolean = false
    /** 空实现清空数量固定为零。 */
    override suspend fun clear(): Int = 0
}

/** 仅供既有 JVM 构造调用兼容的空历史仓库；Hilt 生产始终注入 Room 实现。 */
internal object NoOpTorrentSearchHistoryRepository : TorrentSearchHistoryRepository {
    /** 空实现不产生历史列表。 */
    override fun observeHistory(): Flow<List<TorrentSearchHistory>> = kotlinx.coroutines.flow.flowOf(emptyList())
    /** 空实现不保存查询。 */
    override suspend fun saveQuery(query: String): Boolean = false
    /** 空实现不删除查询。 */
    override suspend fun deleteQuery(query: String): Boolean = false
    /** 空实现清空数量固定为零。 */
    override suspend fun clear(): Int = 0
}

/** 数据写入的低敏日志出口；只接收固定操作和状态 code。 */
internal interface TorrentSearchDataLogger {
    /** 记录数据库写入阶段，不得附带查询、hash、URL 或正文。 */
    fun log(operationCode: String, stateCode: String, affectedCount: Int? = null)
}

/** 默认空日志用于纯 JVM 测试，生产由 Hilt 注入 Android 实现。 */
internal object NoOpTorrentSearchDataLogger : TorrentSearchDataLogger {
    /** 测试不产生运行时日志。 */
    override fun log(operationCode: String, stateCode: String, affectedCount: Int?) = Unit
}

/** Room 书签 Repository，负责稳定身份校验、实体映射和低敏写入日志。 */
internal class BookmarkRepositoryImpl(
    /** 书签数据库契约。 */
    private val dao: BookmarkedTorrentDao,
    /** 不含用户数据的日志出口。 */
    private val logger: TorrentSearchDataLogger = NoOpTorrentSearchDataLogger,
    /** 可替换 UTC 毫秒时间源，测试避免依赖真实时钟。 */
    private val nowMillis: () -> Long,
) : BookmarkRepository {
    /** 映射合法数据库行；损坏 Provider ID 行被安全跳过。 */
    override fun observeBookmarks(): Flow<List<BookmarkedTorrent>> {
        /** entities lambda 逐项容错，单条损坏记录不会终止整个书签流。 */
        return dao.observeAll().map { entities -> entities.mapNotNull(::toDomainBookmark) }
    }

    /** 规范化稳定身份后委托 DAO 精确查询，避免详情页订阅整张书签表。 */
    override fun observeBookmarked(infoHash: String): Flow<Boolean> {
        /** normalizedHash 是 DAO 查询使用的唯一小写稳定身份。 */
        val normalizedHash = normalizeStoredInfoHash(infoHash) ?: return kotlinx.coroutines.flow.flowOf(false)
        return dao.observeExistsByInfoHash(normalizedHash)
    }

    /** 使用规范化 hash 替换同一 torrent 书签。 */
    override suspend fun save(bookmark: BookmarkedTorrent): Boolean {
        /** normalizedHash 是数据库主键允许的 40 位小写十六进制值。 */
        val normalizedHash = normalizeStoredInfoHash(bookmark.infoHash) ?: return false
        logger.log("bookmark_upsert", "started")
        return try {
            dao.upsert(bookmark.toEntity(normalizedHash, nowMillis()))
            logger.log("bookmark_upsert", "completed", 1)
            true
        /** cancellation 分支保持结构化并发语义，同时留下不含用户数据的状态。 */
        } catch (cancellation: CancellationException) {
            logger.log("bookmark_upsert", "cancelled")
            throw cancellation
        /** exception 分支记录固定失败 code 后原样传播。 */
        } catch (exception: Exception) {
            logger.log("bookmark_upsert", "failed")
            throw exception
        }
    }

    /** 规范化后删除书签，非法 hash 直接视为未命中。 */
    override suspend fun delete(infoHash: String): Boolean {
        /** normalizedHash 是 DAO 唯一允许接收的稳定身份。 */
        val normalizedHash = normalizeStoredInfoHash(infoHash) ?: return false
        logger.log("bookmark_delete", "started")
        return try {
            /** affectedCount 是 Room 返回的真实删除行数。 */
            val affectedCount = dao.deleteByInfoHash(normalizedHash)
            logger.log("bookmark_delete", "completed", affectedCount)
            affectedCount > 0
        /** cancellation 分支不应被误记为数据库失败。 */
        } catch (cancellation: CancellationException) {
            logger.log("bookmark_delete", "cancelled")
            throw cancellation
        /** exception 分支保留 DAO 原始失败契约。 */
        } catch (exception: Exception) {
            logger.log("bookmark_delete", "failed")
            throw exception
        }
    }

    /** 清空书签并记录低敏数量。 */
    override suspend fun clear(): Int {
        logger.log("bookmark_clear", "started")
        return try {
            /** affectedCount 是 Room 返回的真实删除行数。 */
            val affectedCount = dao.clear()
            logger.log("bookmark_clear", "completed", affectedCount)
            affectedCount
        /** cancellation 分支不吞掉协程取消。 */
        } catch (cancellation: CancellationException) {
            logger.log("bookmark_clear", "cancelled")
            throw cancellation
        /** exception 分支只记录固定状态，不输出数据内容。 */
        } catch (exception: Exception) {
            logger.log("bookmark_clear", "failed")
            throw exception
        }
    }
}

/** Room 搜索历史 Repository，复用入口安全规则并维护固定数量。 */
internal class TorrentSearchHistoryRepositoryImpl(
    /** 搜索历史数据库契约。 */
    private val dao: TorrentSearchHistoryDao,
    /** 不含用户数据的日志出口。 */
    private val logger: TorrentSearchDataLogger = NoOpTorrentSearchDataLogger,
    /** 可替换 UTC 毫秒时间源。 */
    private val nowMillis: () -> Long,
) : TorrentSearchHistoryRepository {
    /** 历史条目上限，防止误操作长期膨胀数据库。 */
    private val historyLimit = 100

    /** 把 Room 实体映射成页面领域模型。 */
    override fun observeHistory(): Flow<List<TorrentSearchHistory>> {
        /** entities lambda 只执行无副作用的稳定模型映射。 */
        return dao.observeRecent(historyLimit).map { entities -> entities.map(TorrentSearchHistoryEntity::toDomain) }
    }

    /** 清洗、规范化并保存查询；超限输入只允许页面预填，不进入历史。 */
    override suspend fun saveQuery(query: String): Boolean {
        /** normalizedInput 复用系统 Intent 与 Provider 查询的统一边界。 */
        val normalizedInput = TorrentSearchInputNormalizer.normalize(query)
        if (!normalizedInput.canAutoSearch) return false
        /** normalizedQuery 是跨 locale 稳定的历史主键。 */
        val normalizedQuery = normalizedInput.query.lowercase(Locale.ROOT)
        logger.log("history_upsert", "started")
        return try {
            dao.upsert(
                TorrentSearchHistoryEntity(
                    normalizedQuery = normalizedQuery,
                    query = normalizedInput.query,
                    updatedAtEpochMillis = nowMillis(),
                )
            )
            dao.trimToLimit(historyLimit)
            logger.log("history_upsert", "completed", 1)
            true
        /** cancellation 分支维持调用方取消边界。 */
        } catch (cancellation: CancellationException) {
            logger.log("history_upsert", "cancelled")
            throw cancellation
        /** exception 分支记录失败后继续传播给调用方。 */
        } catch (exception: Exception) {
            logger.log("history_upsert", "failed")
            throw exception
        }
    }

    /** 使用相同清洗和 Locale.ROOT 规则删除历史。 */
    override suspend fun deleteQuery(query: String): Boolean {
        /** normalizedInput 拒绝空白和超限删除请求，避免含糊范围操作。 */
        val normalizedInput = TorrentSearchInputNormalizer.normalize(query)
        if (!normalizedInput.canAutoSearch) return false
        /** normalizedQuery 与表主键规则完全一致。 */
        val normalizedQuery = normalizedInput.query.lowercase(Locale.ROOT)
        logger.log("history_delete", "started")
        return try {
            /** affectedCount 是 DAO 返回的真实删除行数。 */
            val affectedCount = dao.deleteByNormalizedQuery(normalizedQuery)
            logger.log("history_delete", "completed", affectedCount)
            affectedCount > 0
        /** cancellation 分支维持调用方取消边界。 */
        } catch (cancellation: CancellationException) {
            logger.log("history_delete", "cancelled")
            throw cancellation
        /** exception 分支不附带查询内容。 */
        } catch (exception: Exception) {
            logger.log("history_delete", "failed")
            throw exception
        }
    }

    /** 清空历史并记录低敏数量。 */
    override suspend fun clear(): Int {
        logger.log("history_clear", "started")
        return try {
            /** affectedCount 是 DAO 返回的真实删除行数。 */
            val affectedCount = dao.clear()
            logger.log("history_clear", "completed", affectedCount)
            affectedCount
        /** cancellation 分支维持调用方取消边界。 */
        } catch (cancellation: CancellationException) {
            logger.log("history_clear", "cancelled")
            throw cancellation
        /** exception 分支只记录固定状态。 */
        } catch (exception: Exception) {
            logger.log("history_clear", "failed")
            throw exception
        }
    }
}

/** Room 已查看 Repository，负责稳定 hash 规范化与最近时间刷新。 */
internal class ViewedTorrentRepositoryImpl(
    /** 已查看数据库契约。 */
    private val dao: ViewedTorrentDao,
    /** 不含用户数据的日志出口。 */
    private val logger: TorrentSearchDataLogger = NoOpTorrentSearchDataLogger,
    /** 可替换 UTC 毫秒时间源。 */
    private val nowMillis: () -> Long,
) : ViewedTorrentRepository {
    /** 把 Room 实体映射成页面领域模型。 */
    override fun observeViewed(): Flow<List<ViewedTorrent>> {
        /** entities lambda 只执行无副作用的稳定模型映射。 */
        return dao.observeAll().map { entities -> entities.map(ViewedTorrentEntity::toDomain) }
    }

    /** 规范化 hash 后插入或刷新最近查看时间。 */
    override suspend fun markViewed(infoHash: String): Boolean {
        /** normalizedHash 是表主键允许的稳定身份。 */
        val normalizedHash = normalizeStoredInfoHash(infoHash) ?: return false
        logger.log("viewed_upsert", "started")
        return try {
            dao.upsert(ViewedTorrentEntity(normalizedHash, nowMillis()))
            logger.log("viewed_upsert", "completed", 1)
            true
        /** cancellation 分支保持结构化并发语义。 */
        } catch (cancellation: CancellationException) {
            logger.log("viewed_upsert", "cancelled")
            throw cancellation
        /** exception 分支记录失败后继续传播。 */
        } catch (exception: Exception) {
            logger.log("viewed_upsert", "failed")
            throw exception
        }
    }

    /** 清空已查看记录并记录低敏数量。 */
    override suspend fun clear(): Int {
        logger.log("viewed_clear", "started")
        return try {
            /** affectedCount 是 DAO 返回的真实删除行数。 */
            val affectedCount = dao.clear()
            logger.log("viewed_clear", "completed", affectedCount)
            affectedCount
        /** cancellation 分支维持调用方取消边界。 */
        } catch (cancellation: CancellationException) {
            logger.log("viewed_clear", "cancelled")
            throw cancellation
        /** exception 分支只记录固定状态。 */
        } catch (exception: Exception) {
            logger.log("viewed_clear", "failed")
            throw exception
        }
    }
}

/** 将书签领域模型写入固定 Room 列协议。 */
private fun BookmarkedTorrent.toEntity(normalizedHash: String, bookmarkedAt: Long): BookmarkedTorrentEntity {
    return BookmarkedTorrentEntity(
        infoHash = normalizedHash,
        name = name,
        providerId = providerId.value,
        providerName = providerName,
        sizeText = size,
        seeders = seeders?.toLong(),
        peers = peers?.toLong(),
        uploadEpochMillis = uploadEpochMillis,
        categoryCode = category?.code,
        descriptionPageUrl = descriptionPageUrl,
        magnetUri = magnetUri,
        fileDownloadLink = fileDownloadLink,
        bookmarkedAtEpochMillis = bookmarkedAt,
    )
}

/** 将合法书签实体转为领域模型；损坏稳定 Provider ID 时返回 null。 */
private fun toDomainBookmark(entity: BookmarkedTorrentEntity): BookmarkedTorrent? {
    /** providerId 必须继续满足稳定 ID 协议，损坏行不应拖垮整个 Flow。 */
    val providerId = runCatching { ProviderId(entity.providerId) }.getOrNull() ?: return null
    return BookmarkedTorrent(
        infoHash = entity.infoHash,
        name = entity.name,
        providerId = providerId,
        providerName = entity.providerName,
        size = entity.sizeText,
        /** value lambda 只接受 UInt 可表达范围，损坏快照降级为空。 */
        seeders = entity.seeders?.takeIf { value -> value in 0L..UInt.MAX_VALUE.toLong() }?.toUInt(),
        /** value lambda 对连接数使用与做种数一致的安全边界。 */
        peers = entity.peers?.takeIf { value -> value in 0L..UInt.MAX_VALUE.toLong() }?.toUInt(),
        uploadEpochMillis = entity.uploadEpochMillis,
        category = entity.categoryCode?.let(Category::fromCode),
        descriptionPageUrl = entity.descriptionPageUrl,
        magnetUri = entity.magnetUri,
        fileDownloadLink = entity.fileDownloadLink,
        bookmarkedAtEpochMillis = entity.bookmarkedAtEpochMillis,
    )
}

/** 将搜索历史实体转为无 Room 依赖领域模型。 */
private fun TorrentSearchHistoryEntity.toDomain(): TorrentSearchHistory {
    return TorrentSearchHistory(query, normalizedQuery, updatedAtEpochMillis)
}

/** 将已查看实体转为无 Room 依赖领域模型。 */
private fun ViewedTorrentEntity.toDomain(): ViewedTorrent {
    return ViewedTorrent(infoHash, viewedAtEpochMillis)
}

/** 规范化并校验数据库稳定 infoHash；只接受 SHA-1 的 40 位十六进制表示。 */
private fun normalizeStoredInfoHash(infoHash: String): String? {
    /** normalizedHash 去除边界空白并统一小写，避免大小写重复行。 */
    val normalizedHash = infoHash.trim().lowercase(Locale.ROOT)
    /** value lambda 校验 SHA-1 十六进制长度与字符集。 */
    return normalizedHash.takeIf { value -> value.length == 40 && value.all(Char::isHexDigit) }
}

/** 判断字符是否属于 ASCII 十六进制，避免 Unicode 数字进入稳定数据库键。 */
private fun Char.isHexDigit(): Boolean {
    return this in '0'..'9' || this in 'a'..'f'
}
