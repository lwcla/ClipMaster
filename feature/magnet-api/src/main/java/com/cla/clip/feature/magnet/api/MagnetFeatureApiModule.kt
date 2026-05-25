package com.cla.clip.feature.magnet.api

import dagger.Module
import dagger.multibindings.Multibinds
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** API 模块默认只提供空集合，真实磁力实现由 `:feature:magnet` 通过 multibinding 加入。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MagnetFeatureApiModule {
    @Multibinds
    abstract fun bindMagnetFeatureEntries(): Set<@JvmSuppressWildcards MagnetFeatureEntry>
}
