package com.cla.clip.base.general.di

import com.cla.clip.base.general.repository.ClipDao
import com.cla.clip.base.general.repository.ClipDaoImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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