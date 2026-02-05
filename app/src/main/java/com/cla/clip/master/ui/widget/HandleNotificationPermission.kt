package com.cla.clip.master.ui.widget

import android.Manifest
import android.app.Activity
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cla.clip.base.general.PermissionUtils
import com.cla.clip.base.general.hasPermission
import com.cla.clip.base.general.toPermissionSetting
import com.cla.clip.master.R
import com.cla.clip.master.ui.theme.LwlDemoTheme

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
    val owner = LocalLifecycleOwner.current

    val permission = Manifest.permission.POST_NOTIFICATIONS
    var hasPermission by remember { mutableStateOf(context.hasPermission(permission)) }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                // 在 onResume 时检查权限状态
                val new = context.hasPermission(permission)
                Log.i("通知权限", "HandleNotificationPermission: ON_RESUME 通知权限状态 $new")
                hasPermission = new
            }
        }

        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    if (hasPermission) {
        // 已经有权限，直接退出
        Log.i("通知权限", "HandleNotificationPermission: 已经有通知权限了")
        return
    }

    var showRationaleDialog by remember { mutableStateOf(false) }
    var requestTime = 0L

    // 2. 注册权限回调
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            Log.d("通知权限", "通知权限是否授予: $isGranted")
            if (isGranted) {
                hasPermission = true
                return@rememberLauncherForActivityResult
            }

            // 这是一个系统 API，如果用户之前点了“不再询问”，这个方法会返回 false
            val curTime = System.currentTimeMillis()
            Log.d("通知权限", "HandleNotificationPermission: 通知权限是否被永久拒绝 takeTime=${curTime - requestTime}")

            if (curTime - requestTime < PermissionUtils.DENIED_FOREVER_TAKE_TIME) {
                // 被永久拒绝，显示解释弹窗
                showRationaleDialog = true
            }
        }
    )

    // 3. 监听触发信号，执行初次检查与请求
    LaunchedEffect(Unit) { // Key 使用 Unit，配合外层的 if (!trigger) return，确保只在组件进入组合且 satisfied 时运行一次
        if (!context.hasPermission(permission)) {
            Log.d("通知权限", "触发条件满足，正在请求通知权限...")
            requestTime = System.currentTimeMillis()
            notificationPermissionLauncher.launch(permission)
        }
    }

    // 4. 显示解释弹窗 UI
    if (showRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showRationaleDialog = false },
            title = { Text(stringResource(R.string.host_notification_permission_is_required)) },
            text = { Text(stringResource(R.string.host_notification_permission_tip_dialog)) },
            confirmButton = {
                TextButton(onClick = {
                    showRationaleDialog = false
                    context.toPermissionSetting(permission)
                }) {
                    Text(stringResource(com.cla.clip.base.general.R.string.base_general_to_authorize_again))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRationaleDialog = false }) {
                    Text(stringResource(com.cla.clip.base.general.R.string.base_general_cancel))
                }
            }
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp), // 所有子元素间隔 8dp
        modifier = Modifier
            .clickable(true, onClick = {
                Log.d("通知权限", "手动点击，去请求通知权限")
                requestTime = System.currentTimeMillis()
                notificationPermissionLauncher.launch(permission)
            })
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.host_notification_permission_tip),
            fontWeight = FontWeight.Bold,
            color = Color.Red,
            modifier = Modifier.weight(1f) // 让文字占据剩余空间，把开关挤到右边
        )

        Text(stringResource(com.cla.clip.base.general.R.string.base_general_to_authorize).plus(">>"))
    }
}

@Preview
@Composable
fun PreviewHandleNotificationPermission() {
    LwlDemoTheme {
        HandleNotificationPermission(trigger = true)
    }
}