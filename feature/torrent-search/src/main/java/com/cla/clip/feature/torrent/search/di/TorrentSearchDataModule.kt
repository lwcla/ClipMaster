package com.cla.clip.feature.torrent.search.di

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.cla.clip.feature.torrent.search.backup.AppSettingTorrentSearchBackupDirtyMarker
import com.cla.clip.feature.torrent.search.backup.TorrentSearchBackupDirtyMarker
import com.cla.clip.feature.torrent.search.data.local.BookmarkedTorrentDao
import com.cla.clip.feature.torrent.search.data.document.AndroidBookmarkDocumentGateway
import com.cla.clip.feature.torrent.search.data.document.BookmarkDocumentGateway
import com.cla.clip.feature.torrent.search.data.document.AndroidTorrentDocumentGateway
import com.cla.clip.feature.torrent.search.data.document.TorrentDocumentGateway
import com.cla.clip.feature.torrent.search.data.local.TorrentSearchDatabase
import com.cla.clip.feature.torrent.search.data.local.TorrentSearchHistoryDao
import com.cla.clip.feature.torrent.search.data.local.ViewedTorrentDao
import com.cla.clip.feature.torrent.search.data.repository.BookmarkRepository
import com.cla.clip.feature.torrent.search.data.repository.BookmarkRepositoryImpl
import com.cla.clip.feature.torrent.search.data.repository.TorrentSearchDataLogger
import com.cla.clip.feature.torrent.search.data.repository.TorrentSearchHistoryRepository
import com.cla.clip.feature.torrent.search.data.repository.TorrentSearchHistoryRepositoryImpl
import com.cla.clip.feature.torrent.search.data.repository.ViewedTorrentRepository
import com.cla.clip.feature.torrent.search.data.repository.ViewedTorrentRepositoryImpl
import com.cla.clip.feature.torrent.search.legacy.LegacyMagnetCleanupLogger
import com.cla.clip.feature.torrent.search.legacy.LegacyMagnetDataCleaner
import com.cla.clip.feature.torrent.search.settings.TorrentSearchSettingsRepository
import com.cla.clip.feature.torrent.search.settings.TorrentSearchDnsModeApplier
import com.cla.clip.feature.torrent.search.torznab.security.AndroidKeystoreTorznabApiKeyCipher
import com.cla.clip.feature.torrent.search.torznab.security.EncryptedTorznabApiKeyStore
import com.cla.clip.feature.torrent.search.torznab.security.SharedPreferencesTorznabEncryptedSecretBlobStore
import com.cla.clip.feature.torrent.search.torznab.security.TorznabApiKeyStore
import com.cla.clip.feature.torrent.search.torznab.security.TorznabSecurityLogger
import com.cla.clip.feature.torrent.search.torznab.TorznabConfigManager
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

/** TorrentSearch 设置 DataStore 限定符，避免与宿主或后续其他 Preferences 绑定冲突。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class TorrentSearchSettingsStore

/** Android 数据日志实现，只输出固定操作、状态和数量字段。 */
private object AndroidTorrentSearchDataLogger : TorrentSearchDataLogger {
    /** 日志 TAG 不含用户输入或路径。 */
    private const val TAG = "TorrentSearchData"

    /** 输出低敏数据库阶段；失败使用 warning，其余使用 debug。 */
    override fun log(operationCode: String, stateCode: String, affectedCount: Int?) {
        /** message 只由固定 code 和可选数量组成。 */
        val message = "数据操作 operationCode=$operationCode stateCode=$stateCode affectedCount=${affectedCount ?: -1}"
        if (stateCode == "failed") Log.w(TAG, message) else Log.d(TAG, message)
    }
}

/** Android Torznab 安全日志实现，只输出固定阶段和 reasonCode。 */
private object AndroidTorznabSecurityLogger : TorznabSecurityLogger {
    /** 日志 TAG 不含配置或密钥数据。 */
    private const val TAG = "TorrentSearchSecret"

    /** 输出低敏安全阶段；失败/降级使用 warning，其余使用 debug。 */
    override fun log(operationCode: String, stateCode: String, reasonCode: String?) {
        /** message 只由固定 code 组成。 */
        val message = "Torznab 密钥操作 operationCode=$operationCode stateCode=$stateCode reasonCode=${reasonCode ?: "none"}"
        if (stateCode == "failed" || stateCode == "fallback") Log.w(TAG, message) else Log.d(TAG, message)
    }
}

/** 旧磁力清理日志只输出固定阶段、状态和文件数量。 */
private object AndroidLegacyMagnetCleanupLogger : LegacyMagnetCleanupLogger {
    /** 日志 TAG 不包含路径或文件名。 */
    private const val TAG = "TorrentSearchLegacy"

    /** 失败用 warning，其余阶段使用 debug。 */
    override fun log(operationCode: String, stateCode: String, affectedCount: Int) {
        /** message 只由固定 code 和数量组成。 */
        val message = "旧数据操作 operationCode=$operationCode stateCode=$stateCode affectedCount=$affectedCount"
        if (stateCode == "failed") Log.w(TAG, message) else Log.d(TAG, message)
    }
}

/** TorrentSearch 独立数据依赖，不向宿主主库或 API 模块泄漏实现类型。 */
@Module
@InstallIn(SingletonComponent::class)
internal object TorrentSearchDataModule {
    /** 独立数据库固定文件名，已从系统 Auto Backup 与设备迁移整体排除。 */
    private const val DATABASE_NAME = "torrent_search_feature.db"
    /** DataStore 逻辑文件名；Android 实际文件固定追加 .preferences_pb。 */
    private const val SETTINGS_NAME = "torrent_search_settings"

    /** 提供 feature-local Room v1 单例；首次构造只打开本地文件，不联网。 */
    @Provides
    @Singleton
    fun provideDatabase(
        /** 应用 Context 保证数据库生命周期覆盖独立 Activity。 */
        @ApplicationContext context: Context,
    ): TorrentSearchDatabase = Room.databaseBuilder(context, TorrentSearchDatabase::class.java, DATABASE_NAME).build()

    /** 提供书签 DAO。 */
    @Provides
    fun provideBookmarkedTorrentDao(database: TorrentSearchDatabase): BookmarkedTorrentDao = database.bookmarkedTorrentDao()

    /** 提供搜索历史 DAO。 */
    @Provides
    fun provideHistoryDao(database: TorrentSearchDatabase): TorrentSearchHistoryDao = database.torrentSearchHistoryDao()

    /** 提供已查看 DAO。 */
    @Provides
    fun provideViewedDao(database: TorrentSearchDatabase): ViewedTorrentDao = database.viewedTorrentDao()

    /** 提供低敏数据日志出口。 */
    @Provides
    fun provideDataLogger(): TorrentSearchDataLogger = AndroidTorrentSearchDataLogger

    /** 提供宿主统一备份 dirty 标记适配器，不在 feature 内调度 Worker。 */
    @Provides
    fun provideBackupDirtyMarker(): TorrentSearchBackupDirtyMarker = AppSettingTorrentSearchBackupDirtyMarker

    /** 提供只访问应用私有 databases/cache 固定目标的旧磁力清理器。 */
    @Provides
    @Singleton
    fun provideLegacyMagnetDataCleaner(
        /** 应用 Context 用于解析私有目录，不把路径传给页面。 */
        @ApplicationContext context: Context,
    ): LegacyMagnetDataCleaner {
        /** databaseDirectory 通过旧主文件的系统数据库路径取得精确父目录。 */
        val databaseDirectory = requireNotNull(context.getDatabasePath("magnet_feature_database.db").parentFile)
        return LegacyMagnetDataCleaner(databaseDirectory, context.cacheDir, AndroidLegacyMagnetCleanupLogger)
    }

    /** 提供 feature-local 一次性 SAF 文档网关，禁止页面直接触碰 ContentResolver。 */
    @Provides
    @Singleton
    fun provideBookmarkDocumentGateway(
        /** Android 实现仅持有应用 Context，不持有任何用户选择 URI。 */
        gateway: AndroidBookmarkDocumentGateway,
    ): BookmarkDocumentGateway = gateway

    /** 提供 Torrent 下载的一次性 SAF 写出网关，复用可取消分块流实现。 */
    @Provides
    @Singleton
    fun provideTorrentDocumentGateway(
        /** Android 实现仅持有应用 Context，不持有用户选择 URI。 */
        gateway: AndroidTorrentDocumentGateway,
    ): TorrentDocumentGateway = gateway

    /** 提供 feature-local Preferences DataStore 单例；应用级 SupervisorJob 随进程结束释放。 */
    @Provides
    @Singleton
    @TorrentSearchSettingsStore
    fun provideSettingsDataStore(
        /** 应用 Context 用于定位独立设置文件。 */
        @ApplicationContext context: Context,
    ): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { context.preferencesDataStoreFile(SETTINGS_NAME) },
        )
    }

    /** 提供设置 Repository，页面和后续备份只消费强类型快照。 */
    @Provides
    @Singleton
    fun provideSettingsRepository(
        /** feature-local DataStore 限定绑定。 */
        @TorrentSearchSettingsStore dataStore: DataStore<Preferences>,
        /** 低敏数据日志出口。 */
        logger: TorrentSearchDataLogger,
        /** 共享 DNS 路由只接收稳定模式，不在设置读取时主动联网。 */
        dnsModeApplier: TorrentSearchDnsModeApplier,
        /** 只有排序白名单变化会通过该边界标记 dirty。 */
        backupDirtyMarker: TorrentSearchBackupDirtyMarker,
    ): TorrentSearchSettingsRepository = TorrentSearchSettingsRepository(dataStore, logger, dnsModeApplier, backupDirtyMarker)

    /** 提供书签 Repository，并把真实时钟限制在依赖装配层。 */
    @Provides
    @Singleton
    fun provideBookmarkRepository(
        /** 书签 Room 契约。 */
        dao: BookmarkedTorrentDao,
        /** 固定 code 低敏日志。 */
        logger: TorrentSearchDataLogger,
        /** 跨安装数据变化标记。 */
        backupDirtyMarker: TorrentSearchBackupDirtyMarker,
    ): BookmarkRepository {
        return BookmarkRepositoryImpl(
            dao = dao,
            logger = logger,
            nowMillis = System::currentTimeMillis,
            backupDirtyMarker = backupDirtyMarker,
        )
    }

    /** 提供搜索历史 Repository，并把真实时钟限制在依赖装配层。 */
    @Provides
    @Singleton
    fun provideHistoryRepository(
        /** 历史 Room 契约。 */
        dao: TorrentSearchHistoryDao,
        /** 固定 code 低敏日志。 */
        logger: TorrentSearchDataLogger,
        /** 跨安装数据变化标记。 */
        backupDirtyMarker: TorrentSearchBackupDirtyMarker,
    ): TorrentSearchHistoryRepository {
        return TorrentSearchHistoryRepositoryImpl(
            dao = dao,
            logger = logger,
            nowMillis = System::currentTimeMillis,
            backupDirtyMarker = backupDirtyMarker,
        )
    }

    /** 提供已查看 Repository，并把真实时钟限制在依赖装配层。 */
    @Provides
    @Singleton
    fun provideViewedRepository(
        /** 已查看 Room 契约。 */
        dao: ViewedTorrentDao,
        /** 固定 code 低敏日志。 */
        logger: TorrentSearchDataLogger,
        /** 跨安装数据变化标记。 */
        backupDirtyMarker: TorrentSearchBackupDirtyMarker,
    ): ViewedTorrentRepository {
        return ViewedTorrentRepositoryImpl(
            dao = dao,
            logger = logger,
            nowMillis = System::currentTimeMillis,
            backupDirtyMarker = backupDirtyMarker,
        )
    }

    /** 提供 Android Keystore + 独立密文介质组合的 API Key 仓库。 */
    @Provides
    @Singleton
    fun provideTorznabApiKeyStore(
        /** 应用 Context 用于打开已排除备份的私有 SharedPreferences。 */
        @ApplicationContext context: Context,
    ): TorznabApiKeyStore {
        /** cipher 只持有系统 Keystore alias，不持有 Context 或明文。 */
        val cipher = AndroidKeystoreTorznabApiKeyCipher()
        /** blobStore 只保存版本化 IV 与密文。 */
        val blobStore = SharedPreferencesTorznabEncryptedSecretBlobStore.create(context)
        return EncryptedTorznabApiKeyStore(cipher, blobStore, AndroidTorznabSecurityLogger)
    }

    /** 提供配置/密钥 IO 协调器，页面不直接访问 Keystore 或 DataStore 组合事务。 */
    @Provides
    @Singleton
    fun provideTorznabConfigManager(
        /** 非敏感设置仓库。 */
        settingsRepository: TorrentSearchSettingsRepository,
        /** 独立加密密钥仓库。 */
        apiKeyStore: TorznabApiKeyStore,
    ): TorznabConfigManager = TorznabConfigManager(settingsRepository, apiKeyStore, Dispatchers.IO)
}
