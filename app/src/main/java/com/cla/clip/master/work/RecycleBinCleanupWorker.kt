package com.cla.clip.master.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * 回收站过期数据清理 Worker。
 *
 * Worker 只按当前保留天数静默删除过期回收站记录，不发通知、不弹 Toast；用户主动在设置页保存保留天数时由页面侧反馈结果。
 */
@HiltWorker
class RecycleBinCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    /** 剪贴仓库使用 Lazy，避免 Worker 创建时立即触发数据库依赖，实际执行时再读取。 */
    private val clipRepository: Lazy<ClipRepository>,
) : CoroutineWorker(appContext, params) {

    companion object {
        /** Worker 日志标签，用于定位后台回收站清理结果。 */
        private const val TAG = "RecycleBinCleanupWorker"
    }

    /** 按当前设置执行一次过期清理；失败时交给 WorkManager 按策略重试。 */
    override suspend fun doWork(): Result {
        return runCatching {
            val days = AppSetting.recycleBinRetentionDays
            val count = clipRepository.get().cleanupExpiredRecycleBinClips(days)
            logD(TAG) { "doWork: 清理过期回收站数据 days=$days count=$count" }
            Result.success()
        }.getOrElse { throwable ->
            logE(TAG, throwable) { "doWork: 清理过期回收站数据失败" }
            Result.retry()
        }
    }
}

/**
 * 回收站清理任务调度入口。
 *
 * 应用启动时注册每日周期任务并立即清理一次，确保长期不打开回收站时也能按设置清理过期数据。
 */
object RecycleBinCleanupScheduler {
    /** 每日周期清理任务唯一名称，避免重复入队多个后台清理任务。 */
    private const val UNIQUE_PERIODIC_NAME = "recycle_bin_cleanup_periodic"

    /** 启动时立即清理任务唯一名称，用 REPLACE 保证最新设置尽快生效。 */
    private const val UNIQUE_IMMEDIATE_NAME = "recycle_bin_cleanup_immediate"

    /** 注册每日清理任务；WorkManager 周期任务按 24 小时间隔运行。 */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<RecycleBinCleanupWorker>(
            24, TimeUnit.HOURS
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** 立即执行一次清理，通常在应用启动后调用。 */
    fun cleanupNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<RecycleBinCleanupWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_IMMEDIATE_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
