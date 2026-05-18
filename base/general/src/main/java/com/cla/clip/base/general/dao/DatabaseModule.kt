package com.cla.clip.base.general.dao

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt模块，负责提供数据库相关的依赖。
 * 安装在 SingletonComponent 中，意味着这里提供的所有实例都是应用级别的单例。
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * 提供AppDatabase的单例。
     * Hilt会自动传入应用上下文 @ApplicationContext。
     *
     * @param context 应用上下文。
     * @return AppDatabase的唯一实例。
     */
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "clip_master_database" // 数据库文件名
        )
            // 4->5 只调整视频 URL 索引唯一性，保留所有既有下载记录和媒体路径。
            // 5->6 为剪贴记录补充折叠状态，旧记录默认保持未折叠。
            // 6->7 为剪贴记录补充回收站删除时间，旧记录默认保持正常可见。
            // 7->8 为折叠记录补充折叠时间，旧折叠记录用原剪贴时间回填以保持稳定顺序。
            // 8->9 新增搜索历史表；历史数据与剪贴记录解耦，升级时不需要回填旧搜索词。
            .addMigrations(
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9
            )
            .build()
    }

    /**
     * 提供ClipDao的单例。
     * Hilt会自动解决它的依赖——AppDatabase。
     *
     * @param appDatabase 由上面的provideAppDatabase方法提供的数据库实例。
     * @return ClipDao的唯一实例。
     */
    @Provides
    @Singleton
    fun provideClipDao(appDatabase: AppDatabase): ClipDao {
        return appDatabase.clipDao()
    }

    /**
     * 提供SourceAppDao的单例。
     * Hilt会自动解决它的依赖——AppDatabase。
     *
     * @param appDatabase 由上面的provideAppDatabase方法提供的数据库实例。
     * @return SourceAppDao的唯一实例。
     */
    @Provides
    @Singleton
    fun provideSourceAppDao(appDatabase: AppDatabase): SourceAppDao {
        return appDatabase.sourceAppDao()
    }

    /**
     * 提供LinkPreviewDao的单例。
     * Hilt会自动解决它的依赖——AppDatabase。
     *
     * @param appDatabase 由上面的provideAppDatabase方法提供的数据库实例。
     * @return LinkPreviewDao的唯一实例。
     */
    @Provides
    @Singleton
    fun provideLinkPreviewDao(appDatabase: AppDatabase): LinkPreviewDao {
        return appDatabase.linkPreviewDao()
    }

    @Provides
    @Singleton
    fun provideDownloadDao(appDatabase: AppDatabase): DownloadDao {
        return appDatabase.downloadDao()
    }

    /** 注入网页图片批量提取 DAO，供 Repository 和 Worker 共享同一批任务数据。 */
    @Provides
    @Singleton
    fun provideImageExtractDao(appDatabase: AppDatabase): ImageExtractDao {
        return appDatabase.imageExtractDao()
    }

    /** 注入搜索历史 DAO，供搜索页历史提示和清理操作访问独立历史表。 */
    @Provides
    @Singleton
    fun provideSearchHistoryDao(appDatabase: AppDatabase): SearchHistoryDao {
        return appDatabase.searchHistoryDao()
    }
}
