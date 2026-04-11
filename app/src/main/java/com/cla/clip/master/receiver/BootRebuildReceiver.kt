package com.cla.clip.master.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logI
import com.cla.clip.master.work.ShizukuWorkScheduler

class BootRebuildReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootRebuildReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {

        logD(TAG) { "onReceive: action=${intent?.action}" }

        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                logI(TAG) { "onReceive: 系统启动" }
                ShizukuWorkScheduler.schedulePeriodic(context)
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                logI(TAG) { "onReceive: 应用升级" }
                ShizukuWorkScheduler.schedulePeriodic(context)
            }
        }
    }
}