package com.cla.clip.feature.torrent.search.di

import android.util.Log
import com.cla.clip.feature.torrent.search.cloudflare.AndroidWebViewCookieHeaderProvider
import com.cla.clip.feature.torrent.search.download.TorrentDownloadCoordinator
import com.cla.clip.feature.torrent.search.download.TorrentDownloadLogger
import com.cla.clip.feature.torrent.search.download.TorrentDownloadPreparer
import com.cla.clip.feature.torrent.search.logging.AndroidProviderLogger
import com.cla.clip.feature.torrent.search.logging.ProviderLogger
import com.cla.clip.feature.torrent.search.network.OkHttpTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.network.SwitchableTorrentSearchDns
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.provider.builtin.BuiltinProviderRegistry
import com.cla.clip.feature.torrent.search.search.SearchCoordinator
import com.cla.clip.feature.torrent.search.settings.TorrentSearchDnsModeApplier
import com.cla.clip.feature.torrent.search.ui.browse.BrowseCoordinator
import com.cla.clip.feature.torrent.search.ui.details.TorrentDetailsLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers

/** TorrentSearch 专用 OkHttp 绑定限定符，防止覆盖或复用宿主的全局网络策略。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class TorrentSearchHttpClient

/** Android Torrent 下载日志，只输出固定阶段、原因与字节数。 */
private object AndroidTorrentDownloadLogger : TorrentDownloadLogger {
    /** 日志 TAG 不包含来源、URL、文件名或用户输入。 */
    private const val TAG = "TorrentDownload"

    /** 失败使用 warning，其余阶段使用 debug。 */
    override fun log(operationCode: String, stateCode: String, reasonCode: String?, byteCount: Int?) {
        /** message 只由固定 code 和可选数量组成。 */
        val message = "Torrent 下载 operationCode=$operationCode stateCode=$stateCode reasonCode=${reasonCode ?: "none"} byteCount=${byteCount ?: -1}"
        if (stateCode == "failed") Log.w(TAG, message) else Log.d(TAG, message)
    }
}

/** 搜索内核 Hilt 提供者；只延迟构造对象，Application 和 Activity 启动期间不发网络。 */
@Module
@InstallIn(SingletonComponent::class)
object TorrentSearchCoreModule {
    /** 提供运行时可切换 DNS；构造 DoH 客户端不执行解析或网络请求。 */
    @Provides
    @Singleton
    internal fun provideSwitchableDns(): SwitchableTorrentSearchDns {
        /** bootstrapClient 固定使用系统 DNS，只负责连接 Cloudflare DoH 端点。 */
        val bootstrapClient = OkHttpTorrentSearchNetworkClient.createOkHttpClient(Dns.SYSTEM)
        /** cloudflareDns 使用固定 HTTPS 端点和字面量 bootstrap，失败时不回退其他公共 DNS。 */
        val cloudflareDns = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)),
                InetAddress.getByAddress(byteArrayOf(1, 0, 0, 1)),
            )
            .post(true)
            .includeIPv6(true)
            .build()
        return SwitchableTorrentSearchDns(Dns.SYSTEM, cloudflareDns)
    }

    /** 以窄接口把同一 DNS 单例交给设置仓库应用模式。 */
    @Provides
    @Singleton
    internal fun provideDnsModeApplier(dns: SwitchableTorrentSearchDns): TorrentSearchDnsModeApplier = dns

    /** 提供共享 OkHttp 客户端，统一 Dispatcher、连接和读取超时配置。 */
    @Provides
    @Singleton
    @TorrentSearchHttpClient
    internal fun provideOkHttpClient(
        /** 可切换 DNS 路由默认系统模式，设置首快照到达后再幂等切换。 */
        dns: SwitchableTorrentSearchDns,
        /** WebView 挑战成功后的 Cookie 只按每个请求 URL 即时读取。 */
        cookieHeaderProvider: AndroidWebViewCookieHeaderProvider,
    ): OkHttpClient = OkHttpTorrentSearchNetworkClient.createOkHttpClient(dns, cookieHeaderProvider)

    /** 提供可替换网络接口；Provider 只能依赖接口而不能自行创建 OkHttpClient。 */
    @Provides
    @Singleton
    fun provideNetworkClient(
        /** 功能内限定的共享客户端，不接管宿主 OkHttp 绑定。 */
        @TorrentSearchHttpClient client: OkHttpClient,
    ): TorrentSearchNetworkClient = OkHttpTorrentSearchNetworkClient(client)

    /** 提供按固定 manifest 顺序构造的 43 个内置 Provider；构造过程不联网。 */
    @Provides
    @Singleton
    fun provideBuiltinProviders(
        /** 功能内共享网络边界，全部内置 Provider 复用同一 Dispatcher 与取消语义。 */
        networkClient: TorrentSearchNetworkClient,
    ): List<SearchProvider> = BuiltinProviderRegistry.create(networkClient)

    /** 提供结构化脱敏日志出口；测试可直接注入 fake Logger。 */
    @Provides
    @Singleton
    fun provideProviderLogger(): ProviderLogger = AndroidProviderLogger()

    /** 提供默认 45 秒超时的协调器；此构造不读取注册表或执行任何请求。 */
    @Provides
    @Singleton
    fun provideSearchCoordinator(logger: ProviderLogger): SearchCoordinator = SearchCoordinator(logger = logger)

    /** 提供下载、大小限制、Bencode 校验与详情身份比对的编排器。 */
    @Provides
    @Singleton
    internal fun provideTorrentDownloadPreparer(
        /** 二进制下载复用功能内共享 HTTP/HTTPS、DNS 和重定向边界。 */
        networkClient: TorrentSearchNetworkClient,
    ): TorrentDownloadPreparer = TorrentDownloadCoordinator(networkClient, Dispatchers.IO, AndroidTorrentDownloadLogger)

    /** 提供默认 45 秒超时的浏览协调器；调用 load 前不执行任何网络。 */
    @Provides
    @Singleton
    internal fun provideBrowseCoordinator(
        /** Browse 操作复用结构化脱敏日志出口。 */
        logger: ProviderLogger,
    ): BrowseCoordinator = BrowseCoordinator(logger = logger)

    /** 提供带同源 HTTPS 与响应身份校验的详情加载器。 */
    @Provides
    @Singleton
    internal fun provideTorrentDetailsLoader(
        /** Details 操作复用结构化脱敏日志出口。 */
        logger: ProviderLogger,
    ): TorrentDetailsLoader = TorrentDetailsLoader(logger = logger)
}
