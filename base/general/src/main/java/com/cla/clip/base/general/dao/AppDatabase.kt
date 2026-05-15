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
    version = 7, // 版本7：剪贴记录增加回收站删除时间，普通范围默认隐藏回收站数据。
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

        /**
         * 版本 5 -> 6 手动迁移。
         *
         * 折叠状态是新增的可见性字段，旧用户升级后所有历史剪贴记录都应继续出现在普通列表和普通搜索中；
         * 因此新增列使用 NOT NULL DEFAULT 0，并补充索引用于普通/折叠范围过滤。
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `clips` ADD COLUMN `is_folded` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_clips_is_folded` ON `clips` (`is_folded`)")
            }
        }

        /**
         * 版本 6 -> 7 手动迁移。
         *
         * 回收站使用 `deleted_at` 软删除字段表达删除时间；旧数据升级后全部保持正常可见，因此默认值为 0。
         * 同时补充单列和组合索引，分别服务回收站分页、普通/折叠范围过滤和数量统计。
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `clips` ADD COLUMN `deleted_at` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_clips_deleted_at` ON `clips` (`deleted_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_clips_deleted_at_is_folded` ON `clips` (`deleted_at`, `is_folded`)")
            }
        }
    }
}
