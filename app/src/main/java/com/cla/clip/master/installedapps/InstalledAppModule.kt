package com.cla.clip.master.installedapps

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 安装应用读取相关依赖绑定；只服务过滤页当前安装应用展示。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class InstalledAppModule {

    /** 绑定主进程 PackageManager 直读实现，避免 ViewModel 依赖 Android API 细节。 */
    @Binds
    @Singleton
    abstract fun bindInstalledAppReader(reader: PackageManagerInstalledAppReader): InstalledAppReader
}
