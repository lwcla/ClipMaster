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

/** 每日生成入口，无网络约束，不读取 dirty，也不请求失败退避。 */
@HiltWorker
class BackupAutoWorker @AssistedInject constructor(
    /** 系统提供的应用上下文。 */
    @Assisted appContext: Context,
    /** 系统提供的周期执行参数。 */
    @Assisted params: WorkerParameters,
    /** 共用的生成和文件交接流程。 */
    private val coordinator: BackupExecutionCoordinator,
) : CoroutineWorker(appContext, params) {
    /** 真正失败结束本次执行，由下一日或用户手动动作开始新备份。 */
    override suspend fun doWork(): Result = try {
        coordinator.generate(automatic = true)
        Result.success()
    } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        logE("BackupAutoWorker") { "每日备份结束 reasonCode=generation_failed type=${failure::class.simpleName}" }
        Result.failure()
    }
}
