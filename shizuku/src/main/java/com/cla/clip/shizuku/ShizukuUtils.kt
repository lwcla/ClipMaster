package com.cla.clip.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import rikka.shizuku.Shizuku

object ShizukuUtils {

    private const val TAG = "ShizukuUtils"

    private const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"

    const val REQUEST_CODE = 1

    /** Shizuku 状态 */
    fun checkStatus(context: Context): ShizukuStatus {
        if (!isShizukuInstalled(context)) {
            return ShizukuStatus.Disconnect.NotInstalled()
        }

        if (!Shizuku.pingBinder()) {
            return ShizukuStatus.Disconnect.ServiceNotAlive()
        }

        if (Shizuku.isPreV11()) {
            return ShizukuStatus.Disconnect.VersionTooLow()
        }

        val isConnected = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrNull() ?: false
        if (isConnected) {
            return ShizukuStatus.Connected()
        }

        return ShizukuStatus.Disconnect.NotGranted()
    }

    /** shizuku 是否已经连接 */
    fun isConnected(context: Context): Boolean {
        return checkStatus(context) is ShizukuStatus.Connected
    }

    fun toConnect() {
        logI(TAG) { "toConnect: toConnect: 去申请授权" }
        Shizuku.requestPermission(REQUEST_CODE)
    }

    /** 跳转到 Shizuku 官网下载页 */
    fun toDownloadApk(context: Context) {
        logI(TAG) { "toDownloadApk: 去下载 Shizuku" }
        val intent = Intent(Intent.ACTION_VIEW, "https://shizuku.rikka.app/download/".toUri())
        context.startActivity(intent)
    }

    /** 跳转到 shizuku */
    fun toShizukuApp(context: Context) {
        logI(TAG) { "toShizukuApp: 跳转到 Shizuku 应用" }
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE_NAME)
        if (intent != null) {
            context.startActivity(intent)
        }
    }

    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            // 尝试获取 Shizuku 的包信息
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE_NAME, 0).also {
                logD(TAG) { "isShizukuInstalled: isShizukuInstalled: 获取到的shizuku包信息：$it" }
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            logE(TAG,e) { "isShizukuInstalled: 没有找到shizuku app" }
            false
        }
    }
}