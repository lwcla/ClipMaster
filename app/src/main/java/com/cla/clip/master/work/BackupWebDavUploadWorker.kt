package com.cla.clip.master.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cla.clip.master.backup.BackupExecutionCoordinator
import com.cla.clip.base.general.utils.logE
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/** 上传已生成的同一份 ZIP；该入口没有数据库导出能力。 */
@HiltWorker
class BackupWebDavUploadWorker @AssistedInject constructor(
    /** 系统应用上下文。 */
    @Assisted appContext: Context,
    /** 包含低敏文件身份的系统参数。 */
    @Assisted params: WorkerParameters,
    /** 上传流程协作者，负责身份验证和终态清理。 */
    private val coordinator: BackupExecutionCoordinator,
) : CoroutineWorker(appContext, params) {
    /** 失败不安排重试；历史结果由协调器持久化。 */
    override suspend fun doWork(): Result = try {
        /** 参数仅包含文件身份，没有路径、配置或密码。 */
        val artifactId = inputData.getString("artifact_id") ?: return Result.failure()
        coordinator.upload(artifactId)
        Result.success()
    } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        logE("BackupWebDavUploadWorker") { "上传任务结束 reasonCode=upload_failed type=${failure::class.simpleName}" }
        Result.failure()
    }
}
