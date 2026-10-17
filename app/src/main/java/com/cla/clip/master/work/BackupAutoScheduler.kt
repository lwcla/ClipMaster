package com.cla.clip.master.work

import android.content.Context
import androidx.work.*
import com.cla.clip.base.general.backup.settings.BackupResultTarget
import com.cla.clip.base.general.backup.settings.BackupSettingsRepository
import com.cla.clip.base.general.backup.settings.BackupWebDavRetryDecision
import com.cla.clip.master.backup.BackupExecutionCoordinator
import com.cla.clip.master.backup.BackupSchedulingPolicy
import com.cla.clip.base.general.utils.logD
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** 启动和配置变化仅维护周期及旧任务兼容，不提前生成或补传。 */
object BackupAutoScheduler {
    /** 保留已有日周期名称，UPDATE 不重置已有等待时间。 */
    private const val PERIODIC_NAME = "backup_auto_periodic"
    /** 手动请求提交后独立于页面生命周期。 */
    private const val MANUAL_NAME = "backup_manual_generation"
    /** 本地目标的唯一队列名，不建立跨目标任务依赖边。 */
    private const val LOCAL_NAME = "backup_local_publish"
    /** 云端目标独立替换自己的旧请求。 */
    private const val UPLOAD_NAME = "backup_webdav_upload"

    /** 每日 24 小时且首次等待 24 小时，自动关闭只取消周期。 */
    suspend fun reschedule(context: Context) {
        /** 复用已有 Hilt 装配，不新建设置实例。 */
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, BackupSchedulingEntryPoint::class.java)
        /** 同一份持久化开关。 */
        val settings = entry.backupSettingsRepository().snapshot()
        /** 系统任务队列，只有备份精确名称会被取消。 */
        val manager = WorkManager.getInstance(context)
        withContext(Dispatchers.IO) {
            manager.cancelUniqueWork("backup_auto_dirty").result.get()
            if (settings.autoBackupEnabled) {
                /** 不设置网络约束，保留历史每日请求的执行入口。 */
                val request = PeriodicWorkRequestBuilder<BackupAutoWorker>(BackupSchedulingPolicy.intervalHours, TimeUnit.HOURS)
                    .setInitialDelay(BackupSchedulingPolicy.intervalHours, TimeUnit.HOURS).build()
                manager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request).result.get()
            } else manager.cancelUniqueWork(PERIODIC_NAME).result.get()
        }
        entry.backupExecutionCoordinator().reconcile()
        logD("BackupAutoScheduler") { "每日任务配置已提交 enabled=${settings.autoBackupEnabled} intervalHours=24" }
    }

    /** 手动只安排生成，返回入队不代表 ZIP 或远端已经完成。 */
    internal suspend fun enqueueManual(context: Context) = withContext(Dispatchers.IO) {
        /** 固定来源 code，兼容没有输入的旧日周期。 */
        val request = OneTimeWorkRequestBuilder<BackupAutoWorker>().setInputData(workDataOf("source" to "manual")).build()
        WorkManager.getInstance(context).enqueueUniqueWork(MANUAL_NAME, ExistingWorkPolicy.REPLACE, request).result.get()
        Unit
    }

    /** 完整包就绪后独立入队；云端等待联网，本地离线执行，双方替换本目标旧请求。 */
    internal suspend fun enqueueTarget(context: Context, roundId: String, target: BackupResultTarget): String = withContext(Dispatchers.IO) {
        /** WorkData 只包含轮次 ID，不含路径、目标或密码。 */
        val request = createTargetRequest(roundId, target)
        WorkManager.getInstance(context).enqueueUniqueWork(
            if (target == BackupResultTarget.Local) LOCAL_NAME else UPLOAD_NAME, ExistingWorkPolicy.REPLACE, request).result.get()
        logD("BackupAutoScheduler") {
            "目标任务已入队 roundId=$roundId target=$target requiresNetwork=${target == BackupResultTarget.WebDav} " +
                "backoffSeconds=${BackupSchedulingPolicy.uploadBackoffSeconds} retryLimit=${BackupWebDavRetryDecision.MAX_RETRIES}"
        }
        request.id.toString()
    }

    /** 构建实际入队请求；纯构建边界供 JVM 验证网络约束及退避，不启动调度器。 */
    internal fun createTargetRequest(roundId: String, target: BackupResultTarget): OneTimeWorkRequest =
        if (target == BackupResultTarget.Local)
            OneTimeWorkRequestBuilder<BackupLocalPublishWorker>().setInputData(workDataOf("round_id" to roundId)).build()
        else OneTimeWorkRequestBuilder<BackupWebDavUploadWorker>()
            .setInputData(workDataOf("round_id" to roundId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(
                if (BackupSchedulingPolicy.uploadRequiresNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BackupSchedulingPolicy.uploadBackoffSeconds, TimeUnit.SECONDS)
            .build()

    /** 尽力取消旧目标，仅等待取消登记，不等待旧 Worker 或 IO 退出。 */
    internal suspend fun cancelTargets(context: Context) = withContext(Dispatchers.IO) {
        /** 同一个应用队列，不影响其他后台工作。 */
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(LOCAL_NAME).result.get()
        manager.cancelUniqueWork(UPLOAD_NAME).result.get()
        Unit
    }

    /** 升级只取消旧身份唯一名，不触发任何网络请求。 */
    internal suspend fun cancelLegacyUpload(context: Context, artifactId: String) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork("backup_webdav_upload_$artifactId").result.get()
        Unit
    }
}

/** 调度只接入既有设置和协调器，不暴露业务数据版本。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackupSchedulingEntryPoint {
    /** 已提交开关。 */
    fun backupSettingsRepository(): BackupSettingsRepository
    /** 仅升级清理，不补入队。 */
    fun backupExecutionCoordinator(): BackupExecutionCoordinator
}
