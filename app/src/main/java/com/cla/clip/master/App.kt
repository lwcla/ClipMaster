package com.cla.clip.master

import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.cla.clip.base.general.BaseApplication
import com.cla.clip.base.general.utils.logI
import com.cla.clip.master.utils.ShizukuConnector
import com.cla.clip.master.work.DownloadVideoWorker
import com.cla.clip.master.work.ShizukuWorkScheduler
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class App : BaseApplication(), Configuration.Provider {


    companion object {
        private const val TAG = "App"
    }

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var shizukuConnector: ShizukuConnector

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (isMainProcess()) {
            // 手动初始化 WorkManager，确保用的是HiltWorkerFactory去执行的初始化
            WorkManager.initialize(this, workManagerConfiguration)
            // 不要在app启动之后自动执行上次遗留的下载任务
            DownloadVideoWorker.cancel(this)

            ShizukuWorkScheduler.schedulePeriodic(this)
            ShizukuWorkScheduler.checkNow(this)

            shizukuConnector.connect()
        }
    }
}
