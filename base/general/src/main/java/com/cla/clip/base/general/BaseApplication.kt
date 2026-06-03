package com.cla.clip.base.general

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import com.cla.clip.base.general.config.MmkvInitializer
import com.cla.clip.base.general.utils.logD


/**
 * 多模块共享的 Application 基类。
 *
 * 负责在主进程确认 MMKV 初始化，并提供主进程判断能力；业务 Application 继承后可以在主进程安全地调度 Worker 或连接服务。
 */
open class BaseApplication : Application() {

    companion object {
        /** 基类 Application 日志标签，用于确认当前进程和 MMKV 初始化路径。 */
        private const val TAG = "BaseApplication"
    }

    /** Application 启动时只在主进程确认 MMKV 初始化，避免辅助进程重复创建配置目录或产生初始化竞争。 */
    override fun onCreate() {
        super.onCreate()
        if (isMainProcess) {
            /** MMKV 根目录；Provider 冷启动可能已提前初始化，这里通过幂等入口复用结果。 */
            val rootDir = MmkvInitializer.ensureInitialized(this, "application_main_process")
            logD(TAG) { "onCreate : mmkv root: $rootDir" }
        }
    }

    /**
     * 判断当前进程是否是应用主进程。
     *
     * Android P 及以上使用系统 API 获取进程名；低版本通过 ActivityManager 查找当前 pid。
     * 如果无法获取进程名会返回 false，调用方应避免在未知进程里执行主进程专属初始化。
     */
    val isMainProcess: Boolean
        get() {
            /** 应用主进程名，按 Android manifest 默认进程约定等于当前包名。 */
            val mainProcessName = packageName
            /** 当前实际进程名；低版本可能读取失败，失败时不能执行主进程专属初始化。 */
            val currentProcessName = currentProcessName()
            logD(TAG) { "当前进程: $currentProcessName, 主进程: $mainProcessName" }
            return currentProcessName == mainProcessName
        }

    /**
     * 读取当前进程名。
     *
     * Android P 及以上使用系统 API 获取进程名；低版本通过 ActivityManager 查找当前 pid。
     * 返回 null 表示无法可信判断，调用方应按非主进程处理。
     */
    private fun currentProcessName(): String? {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                getProcessName()
            }

            else -> {
                /** 当前进程 pid，用于从 ActivityManager 进程列表中匹配进程名。 */
                val pid = android.os.Process.myPid()
                /** 系统进程管理器；低版本没有 Application.getProcessName() 时作为兼容读取入口。 */
                val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                activityManager.runningAppProcesses?.firstOrNull { process -> process.pid == pid }?.processName
            }
        }
    }
}
