package com.cla.clip.master.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logI
import com.cla.clip.master.work.BackupAutoScheduler
import com.cla.clip.master.work.ShizukuWorkScheduler

class BootRebuildReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootRebuildReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {

        logD(TAG) { "收到系统广播 action=${intent?.action}" }

        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                logI(TAG) { "收到系统启动广播" }
                ShizukuWorkScheduler.schedulePeriodic(context)
                BackupAutoScheduler.reschedule(context)
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                logI(TAG) { "收到应用升级广播" }
                ShizukuWorkScheduler.schedulePeriodic(context)
                BackupAutoScheduler.reschedule(context)
            }
        }
    }
}
