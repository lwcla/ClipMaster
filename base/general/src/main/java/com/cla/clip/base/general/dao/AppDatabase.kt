package com.cla.clip.base.general.dao

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 5, // 版本5：下载记录允许同一 video_url 生成多条任务，去掉唯一索引。
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4)
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

    companion object {
        /**
         * 版本 4 -> 5 手动迁移。
         *
         * 下载记录页要求“重新下载”创建新任务记录，因此 `video_url` 不能继续保持唯一索引；
         * 迁移时只替换索引，不改动任何已有下载任务行和媒体路径。
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_download_tasks_video_url`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_tasks_video_url` ON `download_tasks` (`video_url`)")
            }
        }
    }
}
