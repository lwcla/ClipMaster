package com.cla.clip.base.general

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionUtils {
    /**
     * 判断权限是否呗永久拒绝的间隔时间
     * 被拒绝两次之后，oppo的系统会认为该权限被永久拒绝，这之后再去申请权限，就会直接返回拒绝
     * 在这里用回调的时间来判断，如果是用户手动操作的，回调时间应该是比较长的，
     * 如果是系统直接返回的拒绝，那么回调的间隔时间应该是很短的
     */
    const val DENIED_FOREVER_TAKE_TIME = 300


}

/** 检查某个权限是否被授予 */
fun Context.hasPermission(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** 跳转到应用的权限设置页面 */
fun Context.toPermissionSetting(permission: String) {

    fun newIntent(): Intent {
        if (permission == Manifest.permission.POST_NOTIFICATIONS) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    // 部分机型可能还需要 EXTRA_CHANNEL_ID，如果只需要总开关页面，传包名通常足够
                }
            }
        }
        // Android 8.0 以下没有专门的通知设置 Action，回退到应用详情页
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
    }


    val intent = try {
        newIntent()
    } catch (e: Exception) {
        //以此防范极少数魔改 ROM 崩溃，回退到应用详情
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
    }
    startActivity(intent)
}