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
        ).build()
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
}