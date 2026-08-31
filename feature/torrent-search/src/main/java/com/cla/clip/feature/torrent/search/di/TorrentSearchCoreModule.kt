package com.cla.clip.feature.torrent.search.di

import com.cla.clip.feature.torrent.search.logging.AndroidProviderLogger
import com.cla.clip.feature.torrent.search.logging.ProviderLogger
import com.cla.clip.feature.torrent.search.network.OkHttpTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.search.SearchCoordinator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton

/** TorrentSearch 专用 OkHttp 绑定限定符，防止覆盖或复用宿主的全局网络策略。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class TorrentSearchHttpClient

/** 搜索内核 Hilt 提供者；只延迟构造对象，Application 和 Activity 启动期间不发网络。 */
@Module
@InstallIn(SingletonComponent::class)
object TorrentSearchCoreModule {
    /** 提供共享 OkHttp 客户端，统一 Dispatcher、连接和读取超时配置。 */
    @Provides
    @Singleton
    @TorrentSearchHttpClient
    fun provideOkHttpClient(): OkHttpClient = OkHttpTorrentSearchNetworkClient.createOkHttpClient()

    /** 提供可替换网络接口；Provider 只能依赖接口而不能自行创建 OkHttpClient。 */
    @Provides
    @Singleton
    fun provideNetworkClient(
        /** 功能内限定的共享客户端，不接管宿主 OkHttp 绑定。 */
        @TorrentSearchHttpClient client: OkHttpClient,
    ): TorrentSearchNetworkClient = OkHttpTorrentSearchNetworkClient(client)

    /** 提供结构化脱敏日志出口；测试可直接注入 fake Logger。 */
    @Provides
    @Singleton
    fun provideProviderLogger(): ProviderLogger = AndroidProviderLogger()

    /** 提供默认 45 秒超时的协调器；此构造不读取注册表或执行任何请求。 */
    @Provides
    @Singleton
    fun provideSearchCoordinator(logger: ProviderLogger): SearchCoordinator = SearchCoordinator(logger = logger)
}
