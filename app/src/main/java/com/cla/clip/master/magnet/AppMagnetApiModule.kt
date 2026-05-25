package com.cla.clip.master.magnet

import android.content.Context
import com.cla.clip.feature.magnet.api.MagnetDirtyNotifier
import com.cla.clip.master.work.BackupAutoScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** 宿主侧实现磁力模块的 dirty 通知，避免 feature 反向依赖 app 的 Worker 调度器。 */
@Singleton
class AppMagnetDirtyNotifier @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
) : MagnetDirtyNotifier {
    override fun markDirtyAndSchedule() {
        BackupAutoScheduler.markDirtyAndSchedule(appContext)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppMagnetApiModule {
    @Binds
    @Singleton
    abstract fun bindMagnetDirtyNotifier(impl: AppMagnetDirtyNotifier): MagnetDirtyNotifier
}
