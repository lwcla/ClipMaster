package com.cla.clip.master.work

import android.content.Context
import androidx.work.*
import com.cla.clip.base.general.backup.settings.BackupPendingUpload
import com.cla.clip.base.general.backup.settings.BackupSettingsRepository
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

/** 仅启动、启动广播和备份配置变更接入；业务保存不再调用调度。 */
object BackupAutoScheduler {
    /** 保留周期任务名称，升级更新其约束而不创建重复任务。 */
    private const val PERIODIC_NAME = "backup_auto_periodic"

    /** 更新日周期并清除旧数据变化任务；不会提前生成快照。 */
    suspend fun reschedule(context: Context) {
        /** 读取 Hilt 已装配依赖，不创建第二份设置存储。 */
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, BackupSchedulingEntryPoint::class.java)
        /** 同一份已提交策略。 */
        val settings = entry.backupSettingsRepository().snapshot()
        /** 应用级调度器。 */
        val manager = WorkManager.getInstance(context)
        withContext(Dispatchers.IO) {
            // 升级清理旧延迟触发任务，取消本身不生成备份。
            manager.cancelUniqueWork("backup_auto_dirty").result.get()
            if (settings.autoBackupEnabled) {
                /** 无网络约束，首次开启等待完整 24 小时。UPDATE 不重置已有周期。 */
                val request = PeriodicWorkRequestBuilder<BackupAutoWorker>(BackupSchedulingPolicy.intervalHours, TimeUnit.HOURS)
                    .setInitialDelay(BackupSchedulingPolicy.intervalHours, TimeUnit.HOURS)
                    .build()
                manager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request).result.get()
            } else {
                manager.cancelUniqueWork(PERIODIC_NAME).result.get()
            }
        }
        entry.backupExecutionCoordinator().reconcile(refreshConstraints = true)
        logD("BackupAutoScheduler") { "每日任务配置已提交 enabled=${settings.autoBackupEnabled} intervalHours=24" }
    }

    /** 私有包入队后返回低敏 Work UUID 供留证；等待约束与 KEEP/REPLACE 策略保持。 */
    internal suspend fun enqueueUpload(context: Context, pending: BackupPendingUpload, onlyWifi: Boolean, refreshConstraints: Boolean = false): String {
        /** 手动采用任意联网，自动按设置要求无计费网络。 */
        val network = if (BackupSchedulingPolicy.uploadRequiresUnmetered(pending.source, onlyWifi)) NetworkType.UNMETERED else NetworkType.CONNECTED
        /** WorkData 只传低敏身份，Worker 执行时读取匹配的当前凭据。 */
        val request = OneTimeWorkRequestBuilder<BackupWebDavUploadWorker>()
            .setInputData(workDataOf("artifact_id" to pending.artifactId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .build()
        return withContext(Dispatchers.IO) {
            /** 任务队列的终态证据补充状态落盘失败边界，已结束包不能复活。 */
            val manager = WorkManager.getInstance(context)
            /** 存在未结束任务才允许更新等待约束；明确结束时禁止另开同包上传。 */
            val existing = manager.getWorkInfosForUniqueWork(uploadName(pending.artifactId)).get()
            check(BackupSchedulingPolicy.canSchedule(pending.phase, existing.isNotEmpty() && existing.all { it.state.isFinished })) { "upload_work_already_finished" }
            manager.enqueueUniqueWork(uploadName(pending.artifactId),
                if (refreshConstraints) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request).result.get()
            // KEEP 可能保留已有请求，不能把被忽略的新 UUID 冒充真实等待任务。
            if (!refreshConstraints) existing.firstOrNull { !it.state.isFinished }?.id?.toString() ?: request.id.toString()
            else request.id.toString()
        }
    }

    /** 取消指定包，调用方持有任务锁，旧 Worker 无法继续持有文件。 */
    internal suspend fun cancelUpload(context: Context, artifactId: String) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork(uploadName(artifactId)).result.get()
        Unit
    }

    /** 唯一名按文件身份分开，旧请求不能冒领新包。 */
    private fun uploadName(artifactId: String): String = "backup_webdav_upload_$artifactId"
}

/** 调度装配边界，不暴露任何业务数据版本入口。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackupSchedulingEntryPoint {
    /** 本机策略。 */
    fun backupSettingsRepository(): BackupSettingsRepository
    /** 文件状态修复与取消流程。 */
    fun backupExecutionCoordinator(): BackupExecutionCoordinator
}
