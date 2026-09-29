package com.cla.clip.master.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logE
import com.cla.clip.master.work.BackupAutoScheduler
import com.cla.clip.master.work.ShizukuWorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootRebuildReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootRebuildReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {

        logD(TAG) { "收到系统广播 action=${intent?.action}" }

        /** 系统广播动作；非启动/升级事件不创建后台任务。 */
        val action = intent?.action
        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                logI(TAG) { "收到系统启动广播" }
                ShizukuWorkScheduler.schedulePeriodic(context)
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                logI(TAG) { "收到应用升级广播" }
                ShizukuWorkScheduler.schedulePeriodic(context)
            }
        }
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        /** BroadcastReceiver 的异步完成令牌；只有读取并提交 WorkManager 调度后才释放。 */
        val pendingResult = goAsync()
        /** 短生命周期 IO 作用域只服务本次广播，避免阻塞系统主线程。 */
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        receiverScope.launch {
            try {
                BackupAutoScheduler.reschedule(context.applicationContext)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logE(TAG, error) { "系统广播恢复备份调度失败 reasonCode=settings_io_failed" }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
