package com.cla.clip.base.general

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import com.cla.clip.base.general.utils.logD


open class BaseApplication : Application() {

    companion object {
        private const val TAG = "BaseApplication"
    }

    protected fun isMainProcess(): Boolean {
        val mainProcessName = packageName
        val currentProcessName = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                getProcessName()
            }

            else -> {
                val pid = android.os.Process.myPid()
                val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
            }
        }

        logD(TAG) { "当前进程: $currentProcessName, 主进程: $mainProcessName" }
        return currentProcessName == mainProcessName
    }
}