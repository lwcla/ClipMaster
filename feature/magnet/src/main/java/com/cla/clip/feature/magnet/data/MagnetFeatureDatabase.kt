package com.cla.clip.feature.magnet.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 磁力功能独立用户数据库。
 *
 * 仅在 `:feature:magnet` 被编译进应用时创建；包含磁力搜索历史和复制/打开记录，不再写入宿主主库。
 */
@Database(
    entities = [
        MagnetSearchHistoryData::class,
        MagnetDownloadRecordData::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class MagnetFeatureDatabase : RoomDatabase() {
    abstract fun magnetDao(): MagnetDao
}
