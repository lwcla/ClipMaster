package com.cla.clip.base.general.di

import com.cla.clip.base.general.repository.ClipDao
import com.cla.clip.base.general.repository.ClipDaoImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Qualifier
import javax.inject.Singleton


/**
 * Hilt模块，负责提供仓库(Repository)层的依赖绑定。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    /**
     * 将ClipRepository接口与其实现ClipRepositoryImpl绑定。
     * 当有地方请求注入ClipRepository时，Hilt会提供一个ClipRepositoryImpl的实例。
     * 使用 @Binds 可以获得比 @Provides 更高的性能。
     */
    @Binds
    @Singleton
    abstract fun bindClipRepository(clipDaoImpl: ClipDaoImpl): ClipDao
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class M3u8Client

/**
 * 提供 OkHttpClient 单例
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    //  如果M3U8需要高并发下载多个 .ts 分片，可以考虑提供一个专门配置的 OkHttpClient 实例
    @Provides
    @Singleton
    @M3u8Client
    fun provideM3U8Client(): OkHttpClient {
        val logger = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val dispatcher = Dispatcher().apply {
            maxRequests = 32          // 全局最大并发请求
            maxRequestsPerHost = 10    // 单域名最大并发
        }

        return OkHttpClient.Builder()
            .addInterceptor(logger)
            .dispatcher(dispatcher)

            // M3U8 的 .ts 分片通常要并发下载 3~5 个
            .connectionPool(ConnectionPool(
                maxIdleConnections = 20,    // 更多闲置连接
                keepAliveDuration = 5,
                timeUnit = TimeUnit.MINUTES
            ))

            .connectTimeout(30, TimeUnit.SECONDS)    // 连接超时
            .readTimeout(60, TimeUnit.SECONDS)      // 读取超时（大文件下载可能需要）
            .writeTimeout(120, TimeUnit.SECONDS)     // 写入超时

            // 重定向支持（很多 CDN 会有重定向）
            // 自动重定向 CDN 经常会 301/302 重定向
            .followRedirects(true)
            .retryOnConnectionFailure(true)

            // 重试策略（可选，但对网络不稳定很有帮助）
            // 自动重试 网络不稳定时，自动重试连接失败的请求
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logger = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC // 打印完整请求/响应体
        }

        return OkHttpClient.Builder()
            .addInterceptor(logger)

            // 超时配置：大文件下载需要更长的超时
            .connectTimeout(60, TimeUnit.SECONDS)    // 连接超时
            .readTimeout(120, TimeUnit.SECONDS)      // 读取超时（大文件下载可能需要）
            .writeTimeout(120, TimeUnit.SECONDS)     // 写入超时

            // 重定向支持（很多 CDN 会有重定向）
            // 自动重定向 CDN 经常会 301/302 重定向
            .followRedirects(true)
            .followSslRedirects(true)

            // 重试策略（可选，但对网络不稳定很有帮助）
            // 自动重试 网络不稳定时，自动重试连接失败的请求
            .retryOnConnectionFailure(true)
            .build()
    }
}