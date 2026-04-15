package com.cla.clip.master

import android.app.ActivityManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.cla.clip.base.general.BaseApplication
import com.cla.clip.base.general.entity.DownloadRepository
import com.cla.clip.base.general.utils.ApplicationScope
import com.cla.clip.base.general.utils.logD
import com.cla.clip.master.utils.NotificationHelper
import com.cla.clip.master.utils.ShizukuConnector
import com.cla.clip.master.work.ShizukuWorkScheduler
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class App : BaseApplication(), Configuration.Provider {


    companion object {
        private const val TAG = "App"
    }

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var notificationHelper: Lazy<NotificationHelper>

    @Inject
    lateinit var shizukuConnector: ShizukuConnector

    @Inject
    lateinit var downloadRepository: DownloadRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (!isMainProcess()) return

        // 手动初始化 WorkManager，确保用的是HiltWorkerFactory去执行的初始化
        WorkManager.initialize(this, workManagerConfiguration)
        scope.launch(Dispatchers.IO) {
            // 清理异常中断后遗留的 pending 输出文件（不阻塞启动）
            downloadRepository.cleanupOrphanPendingOutputs(this@App)
        }
        ShizukuWorkScheduler.schedulePeriodic(this)
        ShizukuWorkScheduler.checkNow(this)

        shizukuConnector.connect()
    }

    private fun isMainProcess(): Boolean {
        val mainProcessName = packageName
        val currentProcessName = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                getProcessName()
            }

            else -> {
                val pid = android.os.Process.myPid()
                val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
            }
        }

        logD(TAG) { "当前进程: $currentProcessName, 主进程: $mainProcessName" }
        return currentProcessName == mainProcessName
    }
}
