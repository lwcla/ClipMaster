package com.cla.clip.base.general.config.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** 宿主 DataStore 限定符，防止与 TorrentSearch 的独立设置文件混用。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class HostSettingsDataStore

/** 宿主设置文件装配；全进程只创建一个活跃实例。 */
@Module
@InstallIn(SingletonComponent::class)
internal object HostSettingsDataStoreModule {
    /** DataStore 逻辑名；实际文件固定追加 preferences_pb 扩展名。 */
    private const val SETTINGS_NAME = "app_settings"

    /** 提供宿主单例；独立 Job 允许未来显式重置时先关闭本文件作用域。 */
    @Provides
    @Singleton
    @HostSettingsDataStore
    fun provideHostSettingsDataStore(
        /** 应用 Context 确保 Provider 与 Application 获得同一文件位置。 */
        @ApplicationContext context: Context,
    ): DataStore<Preferences> {
        /** 宿主设置文件的 IO 作用域，不绑定任何页面生命周期。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { context.preferencesDataStoreFile(SETTINGS_NAME) },
        )
    }
}
