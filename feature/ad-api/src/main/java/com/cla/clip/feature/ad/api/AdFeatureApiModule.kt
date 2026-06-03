package com.cla.clip.feature.ad.api

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/** 广告 API 默认只提供空广告源集合，真实或调试广告源由独立 adapter 模块通过 multibinding 加入。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AdFeatureApiModule {
    /** 广告源集合绑定点；集合为空时宿主必须自动隐藏所有广告位。 */
    @Multibinds
    abstract fun bindAdSourceEntries(): Set<@JvmSuppressWildcards AdSourceEntry>
}
