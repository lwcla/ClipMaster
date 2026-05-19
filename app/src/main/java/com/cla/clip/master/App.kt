package com.cla.clip.master

import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.cla.clip.base.general.BaseApplication
import com.cla.clip.base.general.utils.logI
import com.cla.clip.master.utils.ShizukuConnector
import com.cla.clip.master.work.BackupAutoScheduler
import com.cla.clip.master.work.DownloadVideoWorker
import com.cla.clip.master.work.RecycleBinCleanupScheduler
import com.cla.clip.master.work.ShizukuWorkScheduler
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
/**
 * 主进程 Application。
 *
 * 负责接入 Hilt、提供 WorkManager 的 HiltWorkerFactory，并在主进程启动时恢复 Shizuku 连接与状态轮询。
 * 这里避免在非主进程重复初始化后台任务，防止多进程场景下重复调度 Worker 或重复连接 Shizuku。
 */
class App : BaseApplication(), Configuration.Provider {


    companion object {
        /** Application 日志标签，仅用于启动和后台调度诊断。 */
        private const val TAG = "App"
    }

    /** WorkManager 使用的 Hilt Worker 工厂，确保带依赖注入的 Worker 能被正确创建。 */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /** Shizuku 连接管理器，主进程启动后用它建立剪贴板读取桥接。 */
    @Inject
    lateinit var shizukuConnector: ShizukuConnector

    /** WorkManager 配置入口，返回带 HiltWorkerFactory 的配置，供手动初始化和系统读取使用。 */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * Application 启动入口。
     *
     * 主进程中手动初始化 WorkManager，取消上次遗留的视频下载任务，调度 Shizuku 状态检查并尝试连接；
     * 非主进程跳过这些动作，避免重复初始化和后台任务竞争。
     */
    override fun onCreate() {
        super.onCreate()
        if (isMainProcess()) {
            // 手动初始化 WorkManager，确保用的是HiltWorkerFactory去执行的初始化
            WorkManager.initialize(this, workManagerConfiguration)
            // 不要在app启动之后自动执行上次遗留的下载任务
            DownloadVideoWorker.cancel(this)

            ShizukuWorkScheduler.schedulePeriodic(this)
            ShizukuWorkScheduler.checkNow(this)
            RecycleBinCleanupScheduler.schedulePeriodic(this)
            RecycleBinCleanupScheduler.cleanupNow(this)
            BackupAutoScheduler.reschedule(this)

            shizukuConnector.connect()
        }
    }
}
