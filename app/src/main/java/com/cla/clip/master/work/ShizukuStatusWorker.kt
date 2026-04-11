package com.cla.clip.master.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.master.utils.NotificationHelper
import com.cla.clip.shizuku.ShizukuUtils
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit


/**
 * 后台巡检任务：检查 Shizuku 状态，并发出状态通知。
 *
 * 说明：
 * - 继承 CoroutineWorker，doWork() 是挂起函数，适合执行 I/O。
 * - Worker 由 WorkManager 创建，不要自己 new。
 */
@HiltWorker
class ShizukuStatusWorker @AssistedInject constructor(
    @Assisted appContext: Context,  // WorkManager 传入的应用级 Context，生命周期独立于 Activity
    @Assisted params: WorkerParameters,   // WorkManager 传入的任务参数（输入数据、运行次数等元信息）
    private val notificationHelper: NotificationHelper,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "ShizukuStatusWorker"
    }

    /**
     * Worker 执行入口。
     *
     * 返回值语义：
     * - Result.success()：任务成功，当前执行结束
     * - Result.retry()：失败后可重试（由系统根据退避策略调度）
     * - Result.failure()：失败且不再重试
     */
    override suspend fun doWork(): Result {
        return runCatching {
            val status = ShizukuUtils.checkStatus(applicationContext)
            logI(TAG) { "doWork： shizuku状态=$status" }
            notificationHelper.notifyShizukuStatus(status)
            Result.success() // 任务成功，不重试。
        }.getOrElse {
            logE(TAG, it) { "检查 Shizuku 状态失败" }
            Result.retry()// 失败后按 WorkManager 策略重试。
        }
    }
}

/**
 * 统一管理 Shizuku 状态巡检任务的调度入口。
 *
 * 设计目标：
 * - 通过 unique work name 防止重复入队
 * - 提供“立即检查”和“周期检查”两种调用方式
 */
object ShizukuWorkScheduler {
    private const val TAG = "ShizukuStatusWorker"

    private const val UNIQUE_PERIODIC_NAME = "shizuku_status_periodic_check"
    private const val UNIQUE_IMMEDIATE_NAME = "shizuku_status_immediate_check"

    /**
     * 注册周期巡检（带 Constraints 版本）。
     *
     * @param context 任意 Context
     *
     * 当前 constraints:
     * - NetworkType.NOT_REQUIRED：无需网络即可执行（对本任务通常影响不大）
     */
    fun schedulePeriodic(context: Context) {
        logI(TAG) { "schedulePeriodic: 开启定时检查shizuku状态的任务" }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        // 周期任务，最小 15 分钟（WorkManager 限制）。
        val request = PeriodicWorkRequestBuilder<ShizukuStatusWorker>(
            15, TimeUnit.MINUTES
        ).setConstraints(constraints).build()

        // 同名周期任务已存在时，更新为新请求（不是再加一条）。
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /**
     * 立即执行一次巡检（一次性任务）。
     *
     * @param context 任意 Context
     *
     * 策略说明：
     * - ExistingWorkPolicy.REPLACE：若已有同名“立即任务”排队/运行，用新任务替换
     */
    fun checkNow(context: Context) {
        logI(TAG) { "checkNow: 立即执行shizuku状态检查" }
        // 立即执行的一次性任务。
        val request = OneTimeWorkRequestBuilder<ShizukuStatusWorker>().build()
        // 同名一次性任务存在时，用新任务替换旧任务。
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_IMMEDIATE_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}