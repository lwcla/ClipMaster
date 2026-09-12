package com.cla.clip.feature.torrent.search.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/** TorrentSearch 独立 Room 数据库；不把可选功能表混入宿主主库。 */
@Database(
    entities = [
        /** 书签表参与 Room v1 schema。 */
        BookmarkedTorrentEntity::class,
        /** 搜索历史表参与 Room v1 schema。 */
        TorrentSearchHistoryEntity::class,
        /** 已查看表参与 Room v1 schema。 */
        ViewedTorrentEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
/** 数据库类型仅在 feature 内暴露，跨层访问必须经由 DAO/Repository。 */
internal abstract class TorrentSearchDatabase : RoomDatabase() {
    /** 提供书签 DAO。 */
    abstract fun bookmarkedTorrentDao(): BookmarkedTorrentDao

    /** 提供搜索历史 DAO。 */
    abstract fun torrentSearchHistoryDao(): TorrentSearchHistoryDao

    /** 提供已查看 DAO。 */
    abstract fun viewedTorrentDao(): ViewedTorrentDao
}
