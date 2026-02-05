package com.cla.clip.master.ui.widget

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * 通知权限处理组件
 * 如果 [trigger] 为 true，则开始检查并请求通知权限。
 * 包含完整的“请求 -> 拒绝 -> 弹窗解释 -> 再请求”闭环逻辑。
 */
@Composable
fun HandleNotificationPermission(trigger: Boolean) {
    // 1. Android 13 以下不需要动态申请，直接退出
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    // 如果触发条件不满足（比如 Shizuku 还没连上），直接退出，不浪费资源
    if (!trigger) return

    val context = LocalContext.current
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
        // 已经有权限，直接退出
        Log.i("通知权限", "HandleNotificationPermission: 已经有通知权限了")
        return
    }

    var showRationaleDialog by remember { mutableStateOf(false) }

    // 2. 注册权限回调
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            Log.d("通知权限", "通知权限是否授予: $isGranted")
            if (!isGranted) {
                // 被拒绝，显示解释弹窗
//                showRationaleDialog = true

                val activity = context as? Activity
                // 这是一个系统 API，如果用户之前点了“不再询问”，这个方法会返回 false
                val shouldShowRationale = activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true
                Log.i("通知权限", "HandleNotificationPermission: 通知权限是否被永久拒绝 shouldShowRationale=$shouldShowRationale activity=$activity")

            }
        }
    )

    // 3. 监听触发信号，执行初次检查与请求
    LaunchedEffect(Unit) { // Key 使用 Unit，配合外层的 if (!trigger) return，确保只在组件进入组合且 satisfied 时运行一次
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Log.d("通知权限", "触发条件满足，正在请求通知权限...")
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 4. 显示解释弹窗 UI
    if (showRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showRationaleDialog = false },
            title = { Text("需要通知权限") },
            text = { Text("为了让您及时收到剪贴板的处理结果，App 需要发送通知。\n请在下一步授权中点击“允许”。") },
            confirmButton = {
                TextButton(onClick = {
                    showRationaleDialog = false
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) {
                    Text("重新授权")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRationaleDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}