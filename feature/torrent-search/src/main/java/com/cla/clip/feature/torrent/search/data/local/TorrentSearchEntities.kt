package com.cla.clip.feature.torrent.search.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** TorrentSearch 书签表；infoHash 是跨 Provider 去重和恢复合并的稳定主键。 */
@Entity(
    tableName = "torrent_search_bookmarks",
    indices = [Index(value = ["bookmarked_at_epoch_millis"])],
)
/** 书签行保存恢复所需结果快照，禁止直接作为页面模型使用。 */
internal data class BookmarkedTorrentEntity(
    /** 规范化为 40 位小写十六进制的 torrent 身份。 */
    @PrimaryKey @ColumnInfo(name = "info_hash") val infoHash: String,
    /** 用户可见 torrent 名称；禁止写日志。 */
    @ColumnInfo(name = "name") val name: String,
    /** 产出结果的稳定 Provider ID。 */
    @ColumnInfo(name = "provider_id") val providerId: String,
    /** Provider 展示名快照，不参与身份或恢复冲突判断。 */
    @ColumnInfo(name = "provider_name") val providerName: String,
    /** 上游展示格式的大小文本；未知时为空。 */
    @ColumnInfo(name = "size_text") val sizeText: String?,
    /** 做种数快照；UInt 通过非负 Long 保存，未知时为空。 */
    @ColumnInfo(name = "seeders") val seeders: Long?,
    /** 连接节点数快照；UInt 通过非负 Long 保存，未知时为空。 */
    @ColumnInfo(name = "peers") val peers: Long?,
    /** 上传时间 UTC epochMillis；未知时为空。 */
    @ColumnInfo(name = "upload_epoch_millis") val uploadEpochMillis: Long?,
    /** 分类稳定 code；未知时为空。 */
    @ColumnInfo(name = "category_code") val categoryCode: String?,
    /** 详情地址快照；只供业务使用且禁止写日志。 */
    @ColumnInfo(name = "description_page_url") val descriptionPageUrl: String?,
    /** 磁力链接快照；禁止写日志与系统 Auto Backup。 */
    @ColumnInfo(name = "magnet_uri") val magnetUri: String?,
    /** torrent 文件下载地址快照；禁止写日志。 */
    @ColumnInfo(name = "file_download_link") val fileDownloadLink: String?,
    /** 最近一次加入或刷新书签的 UTC epochMillis。 */
    @ColumnInfo(name = "bookmarked_at_epoch_millis") val bookmarkedAtEpochMillis: Long,
)

/** TorrentSearch 搜索历史表；规范化查询是去重与幂等恢复的稳定主键。 */
@Entity(
    tableName = "torrent_search_histories",
    indices = [Index(value = ["updated_at_epoch_millis"])],
)
/** 搜索历史行只保留安全清洗后的展示值和稳定去重键。 */
internal data class TorrentSearchHistoryEntity(
    /** 去控制字符、trim 并使用 Locale.ROOT 小写后的稳定查询。 */
    @PrimaryKey @ColumnInfo(name = "normalized_query") val normalizedQuery: String,
    /** 用户最后一次提交的安全展示写法；禁止写日志。 */
    @ColumnInfo(name = "query") val query: String,
    /** 最近提交时间 UTC epochMillis，用于倒序和恢复冲突判断。 */
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long,
)

/** TorrentSearch 已查看表；只保存稳定身份和最近查看时间。 */
@Entity(
    tableName = "torrent_search_viewed",
    indices = [Index(value = ["viewed_at_epoch_millis"])],
)
/** 已查看行不复制标题等内容，减少敏感数据持久化。 */
internal data class ViewedTorrentEntity(
    /** 规范化为 40 位小写十六进制的 torrent 身份。 */
    @PrimaryKey @ColumnInfo(name = "info_hash") val infoHash: String,
    /** 最近查看时间 UTC epochMillis，用于列表标记和过期策略。 */
    @ColumnInfo(name = "viewed_at_epoch_millis") val viewedAtEpochMillis: Long,
)
