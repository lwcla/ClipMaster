package com.cla.clip.base.general.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 权限相关工具和阈值。
 *
 * 集中处理通知、存储权限和系统设置页跳转；不同 Android 版本权限模型不同，调用方不要绕过这里直接判断。
 */
object PermissionUtils {
    /**
     * 判断权限是否呗永久拒绝的间隔时间
     * 被拒绝两次之后，oppo的系统会认为该权限被永久拒绝，这之后再去申请权限，就会直接返回拒绝
     * 在这里用回调的时间来判断，如果是用户手动操作的，回调时间应该是比较长的，
     * 如果是系统直接返回的拒绝，那么回调的间隔时间应该是很短的
     */
    const val DENIED_FOREVER_TAKE_TIME = 400
}

/** 检查普通运行时权限是否已授予；只适用于 Android 标准 dangerous permission，不适用于完整文件访问等特殊权限。 */
fun Context.hasPermission(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * 判断应用当前是否可以发送通知。
 *
 * 通知权限需要同时检查系统通知总开关和 Android 13+ 的运行时权限，否则用户在设置页关闭通知后，
 * 只检查 POST_NOTIFICATIONS 会误判为仍然开启。
 */
fun Context.hasNotificationPermission(): Boolean {
    val notificationEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
    val runtimeGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        hasPermission(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        true
    }
    return notificationEnabled && runtimeGranted
}

/** 判断 Android 13+ 通知运行时权限是否已授予；该结果只影响通知展示，不影响剪贴入库。 */
fun Context.hasNotificationRuntimePermission(): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasPermission(Manifest.permission.POST_NOTIFICATIONS)
}

/**
 * 跳转到当前应用对应权限的系统设置页面。
 *
 * 通知和完整文件访问权限有专属入口，其余权限回退到应用详情页；部分定制 ROM 可能抛异常，因此会兜底到详情页。
 */
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

/**
 * 返回当前系统需要申请的外部存储权限。
 *
 * Android 10+ 只向媒体库写入文件不需要存储权限，因此返回 null；旧系统返回 WRITE_EXTERNAL_STORAGE。
 */
fun Context.getStoragePermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    null
} else {
    // Android 10 以下，申请写权限
    Manifest.permission.WRITE_EXTERNAL_STORAGE
}
