package com.cla.clip.base.general.dao

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 应用的Room数据库主类。
 *
 * @property entities 定义了数据库包含哪些表（实体）。
 * @property version 数据库的版本号。当您修改了表的结构后，必须增加此版本号。
 * @property exportSchema 是否导出数据库结构到JSON文件。强烈建议设为true，这是自动迁移功能的基础。
 * @property autoMigrations 定义了数据库版本之间的自动迁移规则。
 */
@Database(
    entities = [
        ClipData::class,
        ClipFts::class,
        SourceAppData::class,
        LinkPreviewData::class,
        DownloadTaskData::class
    ],
    version = 2, // 版本2：download_tasks 新增 pending_output_uri 字段
    exportSchema = true, // 必须设为true以支持自动迁移
    autoMigrations = [
        AutoMigration(from = 1, to = 2)
    ]
)
abstract class AppDatabase : RoomDatabase() {

    /**
     * 提供对ClipDao的抽象访问方法。
     * Room会自动为我们生成这个方法的具体实现。
     * @return ClipDao的实例。
     */
    abstract fun clipDao(): ClipDao


    /**
     * 提供对SourceAppDao的抽象访问方法。
     * Room会自动为我们生成这个方法的具体实现。
     * @return SourceAppDao的实例。
     */
    abstract fun sourceAppDao(): SourceAppDao

    /**
     * 提供对LinkPreviewDao的抽象访问方法。
     * Room会自动为我们生成这个方法的具体实现。
     * @return LinkPreviewDao的实例。
     */
    abstract fun linkPreviewDao(): LinkPreviewDao

    /**
     * 提供对DownloadDao的抽象访问方法。
     * Room会自动为我们生成这个方法的具体实现。
     * @return DownloadDao的实例。
     */
    abstract fun downloadDao(): DownloadDao
}
