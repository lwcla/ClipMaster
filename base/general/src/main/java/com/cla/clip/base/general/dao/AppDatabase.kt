package com.cla.clip.base.general.dao

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room 数据库入口，集中声明应用内所有表和 DAO。
 *
 * 新增表或字段时需要提升 version，并补充迁移规则，避免用户升级后数据库结构不一致。
 */
@Database(
    entities = [
        ClipData::class,
        ClipFts::class,
        SourceAppData::class,
        LinkPreviewData::class,
        DownloadTaskData::class,
        ImageExtractBatchData::class,
        ImageExtractItemData::class
    ],
    version = 3, // 版本3：新增网页图片批量提取任务表和图片明细表。
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3)
    ]
)
abstract class AppDatabase : RoomDatabase() {

    /** 提供剪贴板数据访问入口。 */
    abstract fun clipDao(): ClipDao

    /** 提供来源应用信息访问入口。 */
    abstract fun sourceAppDao(): SourceAppDao

    /** 提供链接预览数据访问入口。 */
    abstract fun linkPreviewDao(): LinkPreviewDao

    /** 提供视频下载任务访问入口。 */
    abstract fun downloadDao(): DownloadDao

    /** 提供网页图片批量提取任务访问入口。 */
    abstract fun imageExtractDao(): ImageExtractDao
}
