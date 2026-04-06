package com.cla.clip.master

import android.app.ActivityManager
import android.os.Build
import com.cla.clip.base.general.BaseApplication
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.ApplicationScope
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

@HiltAndroidApp
class App : BaseApplication() {


    companion object {
        private const val TAG = "App"
    }

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
    }

    private fun isMainProcess(): Boolean {
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