package com.cla.clip.feature.torrent.search.api

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/** API 模块只声明可为空的入口集合，真实实现仅由启用的实现模块加入。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TorrentSearchFeatureApiModule {
    /** 默认构建提供空集合，让宿主无需运行时总开关即可安全隐藏全部 TorrentSearch 入口。 */
    @Multibinds
    abstract fun bindTorrentSearchFeatureEntries(): Set<@JvmSuppressWildcards TorrentSearchFeatureEntry>
}
