package com.cla.clip.master

import android.app.ActivityManager
import android.os.Build
import com.cla.clip.base.general.BaseApplication
import com.cla.clip.base.general.logD
import com.cla.clip.base.general.utils.ApplicationScope
import com.cla.clip.shizuku.ShizukuManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class App : BaseApplication() {


    companion object {
        private const val TAG = "App"
    }

    @Inject
    lateinit var shizukuManager: dagger.Lazy<ShizukuManager>

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()

        if (isMainProcess()) {
            // 只有主进程需要绑定 Shizuku 服务，其他进程不需要，以避免重复绑定和资源浪费。
            scope.launch {
                delay(1000)
                logD(TAG) { "开始绑定 Shizuku 服务" }
                shizukuManager.get().bindService()
            }
        }
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