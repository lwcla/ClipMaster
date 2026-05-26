package com.cla.clip.master.update

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * App 自升级相关依赖注入模块。
 *
 * 统一把更新链路里的网络抓取器和日志实现绑定到抽象接口，避免调用方直接依赖具体平台实现。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppUpdateModule {
    /** 绑定生产环境使用的 OkHttp manifest 抓取实现。 */
    @Binds
    @Singleton
    abstract fun bindAppUpdateManifestFetcher(fetcher: OkHttpAppUpdateManifestFetcher): AppUpdateManifestFetcher

    /** 绑定 Android 运行时日志实现。 */
    @Binds
    @Singleton
    abstract fun bindAppUpdateLogger(logger: AndroidAppUpdateLogger): AppUpdateLogger
}
