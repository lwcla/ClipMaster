package com.cla.clip.feature.magnet.data

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 提供磁力功能独立用户数据库。 */
@Module
@InstallIn(SingletonComponent::class)
object MagnetDatabaseModule {
    private const val DATABASE_NAME = "magnet_feature_database.db"

    @Provides
    @Singleton
    fun provideMagnetFeatureDatabase(@ApplicationContext context: Context): MagnetFeatureDatabase {
        return Room.databaseBuilder(
            context,
            MagnetFeatureDatabase::class.java,
            DATABASE_NAME
        ).build()
    }

    @Provides
    @Singleton
    fun provideMagnetDao(database: MagnetFeatureDatabase): MagnetDao {
        return database.magnetDao()
    }
}
