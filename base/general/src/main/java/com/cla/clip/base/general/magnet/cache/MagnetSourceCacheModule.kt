package com.cla.clip.base.general.magnet.cache

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** 提供 cacheDir 下的磁力源索引缓存库。 */
@Module
@InstallIn(SingletonComponent::class)
object MagnetSourceCacheModule {
    private const val CACHE_DATABASE_DIR = "magnet_source_cache"
    private const val CACHE_DATABASE_NAME = "magnet_source_cache.db"

    @Provides
    @Singleton
    fun provideMagnetSourceCacheDatabase(@ApplicationContext context: Context): MagnetSourceCacheDatabase {
        val dir = File(context.cacheDir, CACHE_DATABASE_DIR).apply { mkdirs() }
        return Room.databaseBuilder(
            context,
            MagnetSourceCacheDatabase::class.java,
            File(dir, CACHE_DATABASE_NAME).absolutePath
        )
            // 源索引是可重建缓存，版本变化时允许清库重建，不影响主库用户数据。
            .fallbackToDestructiveMigration(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideMagnetSourceCacheDao(database: MagnetSourceCacheDatabase): MagnetSourceCacheDao {
        return database.magnetSourceCacheDao()
    }
}
