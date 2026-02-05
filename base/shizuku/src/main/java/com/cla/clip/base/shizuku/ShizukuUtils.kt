package com.cla.clip.base.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.net.toUri
import rikka.shizuku.Shizuku

object ShizukuUtils {

    private const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"

    const val REQUEST_CODE = 1

    /** Shizuku 是否已连接 */
    val isConnected get() = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun checkStatus(context: Context): ShizukuStatus {
        if (!isShizukuInstalled(context)) {
            return ShizukuStatus.Disconnect.NotInstalled
        }

        if (!Shizuku.pingBinder()) {
            return ShizukuStatus.Disconnect.ServiceNotAlive
        }

        if (Shizuku.isPreV11()) {
            return ShizukuStatus.Disconnect.VersionTooLow
        }

        if (isConnected) {
            return ShizukuStatus.Connected
        }

        return ShizukuStatus.Disconnect.NotGranted
    }

    fun toConnect() {
        Log.i("shizuku", "ShizukuUtils toConnect: 去申请授权")
        Shizuku.requestPermission(REQUEST_CODE)
    }

    /** 跳转到 Shizuku 官网下载页 */
    fun toDownloadApk(context: Context) {
        Log.i("shizuku", "ShizukuUtils toDownloadApk: 去下载 Shizuku")
        val intent = Intent(Intent.ACTION_VIEW, "https://shizuku.rikka.app/download/".toUri())
        context.startActivity(intent)
    }

    /** 跳转到 shizuku */
    fun toShizukuApp(context: Context) {
        Log.i("shizuku", "ShizukuUtils toShizukuApp: 去 Shizuku 应用")
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE_NAME)
        if (intent != null) {
            context.startActivity(intent)
        }
    }

    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            // 尝试获取 Shizuku 的包信息
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE_NAME, 0).also {
                Log.i("shizuku", "ShizukuUtils isShizukuInstalled: 获取到的shizuku包信息：$it")
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e("shizuku", "ShizukuUtils isShizukuInstalled: 没有找到shizuku app", e)
            false
        }
    }

}