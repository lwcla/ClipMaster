package com.cla.clip.master.ui.widget

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cla.clip.base.general.utils.PermissionUtils
import com.cla.clip.base.general.utils.hasNotificationPermission
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.toPermissionSetting
import com.cla.clip.master.R
import com.cla.clip.master.service.ClipboardService

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

    val tag = "通知权限"

    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current

    var hasPermission by remember { mutableStateOf(context.hasNotificationPermission()) }
    var showRationaleDialog by rememberSaveable { mutableStateOf(false) }
    var requestTime by rememberSaveable { mutableLongStateOf(0L) }
    // --- 新增代码：使用 rememberSaveable 记录是否已经自动触发过 ---
    // 这样即使切换主题导致 Activity 重建，这个变量依然会保持为 true，从而阻止 LaunchedEffect 内部逻辑再次运行
    var hasAutoRequested by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                // 在 onResume 时检查权限状态
                val new = context.hasNotificationPermission()
                logI(tag) { "HandleNotificationPermission: ON_RESUME 通知权限状态 $new" }
                hasPermission = new
            }
        }

        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    if (hasPermission) {
        // 已经有权限，直接退出
        logI(tag) { "HandleNotificationPermission: 已经有通知权限了" }
        // 有通知权限了，需要去拉起前台服务
        // 为了确保ClipboardService是存活状态，这里每次都去拉起它
        ClipboardService.start(context)
        return
    }

    // 2. 注册权限回调
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            logD(tag) { "HandleNotificationPermission: 通知权限是否授予: $isGranted" }
            if (isGranted) {
                hasPermission = true
                return@rememberLauncherForActivityResult
            }

            // 这是一个系统 API，如果用户之前点了“不再询问”，这个方法会返回 false
            val curTime = System.currentTimeMillis()
            logD(tag) { "HandleNotificationPermission: 通知权限是否被永久拒绝 takeTime=${curTime - requestTime}" }

            if (curTime - requestTime < PermissionUtils.DENIED_FOREVER_TAKE_TIME) {
                // 被永久拒绝，显示解释弹窗
                showRationaleDialog = true
            }
        }
    )

    val permission = Manifest.permission.POST_NOTIFICATIONS
    // 3. 监听触发信号，执行初次检查与请求
    LaunchedEffect(owner) { // Key 使用 Unit，配合外层的 if (!trigger) return，确保只在组件进入组合且 satisfied 时运行一次
        if (!hasAutoRequested && !context.hasNotificationPermission()) {
            logD(tag) { "触发条件满足，正在请求通知权限..." }
            requestTime = System.currentTimeMillis()
            // 标记位设为 true，下次重建 Activity 时这里依然是 true
            hasAutoRequested = true
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

    Text(
        text = buildAnnotatedString {
            // 第一段：红色
            withStyle(style = SpanStyle(color = Color.Red, fontWeight = FontWeight.Bold)) {
                append(stringResource(R.string.host_notification_permission_tip))
            }

            // 间隔
            append("  ")

            // 第二段：
            withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Normal)) {
                append(stringResource(com.cla.clip.base.general.R.string.base_general_to_authorize).plus(">>"))
            }
        },
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            // 1. 添加圆角边框 (例如: 宽度 1dp, 红色, 8dp 圆角)
            .border(
                width = 1.dp,
                color = Color.Red, // 或者使用 MaterialTheme.colorScheme.error
                shape = RoundedCornerShape(10.dp)
            )
            // 2. 如果希望背景点击效果也遵循圆角，需要 clip
            .clip(RoundedCornerShape(10.dp))
            .clickable(true, onClick = {
                logD(tag) { "手动点击，去请求通知权限" }
                requestTime = System.currentTimeMillis()
                notificationPermissionLauncher.launch(permission)
            })
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}