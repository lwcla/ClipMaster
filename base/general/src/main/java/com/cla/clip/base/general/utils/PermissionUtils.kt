package com.cla.clip.base.general.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionUtils {
    /**
     * 判断权限是否呗永久拒绝的间隔时间
     * 被拒绝两次之后，oppo的系统会认为该权限被永久拒绝，这之后再去申请权限，就会直接返回拒绝
     * 在这里用回调的时间来判断，如果是用户手动操作的，回调时间应该是比较长的，
     * 如果是系统直接返回的拒绝，那么回调的间隔时间应该是很短的
     */
    const val DENIED_FOREVER_TAKE_TIME = 400
}

/** 检查某个权限是否被授予 */
fun Context.hasPermission(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** 是否已经有通知权限了 */
fun Context.hasNotificationPermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    hasPermission(Manifest.permission.POST_NOTIFICATIONS)
} else {
    // Android 13 以下默认有通知权限
    true
}

/** 检查悬浮窗权限 */
fun Context.hasOverlayPermission() = Settings.canDrawOverlays(this)

/** 跳转到应用的权限设置页面 */
fun Context.toPermissionSetting(permission: String) {
    fun newIntent(): Intent {
        when {
            permission == Manifest.permission.POST_NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    }
                }
            }
            // Android 11+ 完整文件访问权限，跳专属页面
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && permission == Manifest.permission.MANAGE_EXTERNAL_STORAGE -> {
                return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            }
        }

        // WRITE_EXTERNAL_STORAGE / READ_EXTERNAL_STORAGE
        // 走下面的默认应用详情页即可
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

/**
 * 检查是否已经有写入外部存储权限
 * 从 Android 10（API 级别 29）开始，应用无需存储权限即可将文件添加到共享存储空间。
 * 这意味着，应用无需请求存储权限，即可将图片添加到图库、录制视频并将其保存到共享存储空间，或下载 PDF 账单。
 * 如果您的应用仅向共享存储空间添加文件，而不会查询图片或视频，您应停止请求存储权限，
 */
fun Context.hasStoragePermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    true
} else {
    // Android 10 以下，申请写权限
    hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
}

fun Context.getStoragePermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    null
} else {
    // Android 10 以下，申请写权限
    Manifest.permission.WRITE_EXTERNAL_STORAGE
}