package com.cla.clip.master

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cla.clip.base.general.logD
import com.cla.clip.master.service.ClipboardService

/**
 * 接收剪贴板数据的广播接收器
 */
class ClipDataReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ClipDataReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra("packageName")
        val appName = intent.getStringExtra("appName")
        val iconBytes = intent.getByteArrayExtra("iconBitmap")

        logD(TAG) { "Received clip data from package: $packageName, appName: $appName, iconBytes size: ${iconBytes?.size}" }
        // 这里可以处理接收到的数据，例如更新UI或者存储数据
        ClipboardService.start(context, packageName ?: "", appName ?: "", iconBytes)
    }
}