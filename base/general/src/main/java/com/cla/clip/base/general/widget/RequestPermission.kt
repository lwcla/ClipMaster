package com.cla.clip.base.general.widget

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cla.clip.base.general.R
import com.cla.clip.base.general.utils.PermissionUtils
import com.cla.clip.base.general.utils.getStoragePermission
import com.cla.clip.base.general.utils.hasStoragePermission
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.toPermissionSetting


/** 请求存储权限；结束回调仅报告本轮授权结果，默认调用方保持既有行为。 */
@Composable
fun RequestStoragePermission(
    onFinished: (Boolean) -> Unit = {},
    next: () -> Unit,
) {
    RequestPermission(
        hasPermission = { hasStoragePermission() },
        getPermission = { getStoragePermission() },
        requestTitle = stringResource(R.string.base_general_storage_permission_is_required),
        requestText = stringResource(R.string.base_general_saving_data_to_a_local_folder_requires_storage_permissions),
        onFinished = onFinished,
        next = next
    )
}

/** 请求权限；保留系统申请与设置返回流程，并在正常结束或拒绝时通知可选调用方。 */
@Composable
fun RequestPermission(
    hasPermission: Context.() -> Boolean,
    getPermission: Context.() -> String?,
    requestTitle: String,
    requestText: String,
    onFinished: (Boolean) -> Unit = {},
    next: () -> Unit
) {
    val tag = "权限请求"
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var hasPermission by remember { mutableStateOf(context.hasPermission()) }
    var requestTime by rememberSaveable { mutableLongStateOf(0L) }
    var showRationaleDialog by rememberSaveable { mutableStateOf(false) }
    // 这样即使切换主题导致 Activity 重建，这个变量依然会保持为 true，从而阻止 LaunchedEffect 内部逻辑再次运行
    var hasAutoRequested by rememberSaveable { mutableStateOf(false) }
    // 从系统设置返回仍未授权时通知调用方结束本轮等待，避免页面永久停在权限抑制状态。
    var awaitingSettingsResult by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                // 在 onResume 时检查权限状态
                val new = context.hasPermission()
                logI(tag) { "RequestPermission: ON_RESUME 权限状态 $new" }
                hasPermission = new
                if (awaitingSettingsResult) {
                    awaitingSettingsResult = false
                    if (!new) onFinished(false)
                }
            }
        }

        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    if (hasPermission) {
        onFinished(true)
        next()
        return
    }

    val permission = context.getPermission()
    if (permission.isNullOrBlank()) {
        onFinished(true)
        next()
        return
    }

    // 2. 注册权限回调
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            logD(tag) { "RequestPermission: 权限是否授予: $isGranted" }
            if (isGranted) {
                hasPermission = true
                return@rememberLauncherForActivityResult
            }

            // 这是一个系统 API，如果用户之前点了“不再询问”，这个方法会返回 false
            val curTime = System.currentTimeMillis()
            logD(tag) { "RequestPermission: 权限是否被永久拒绝 takeTime=${curTime - requestTime}" }

            if (curTime - requestTime < PermissionUtils.DENIED_FOREVER_TAKE_TIME) {
                // 被永久拒绝，显示解释弹窗
                showRationaleDialog = true
            } else {
                // 普通拒绝已经结束本轮系统弹窗，调用方可以恢复页面交互与广告资格。
                onFinished(false)
            }
        }
    )

    // 3. 监听触发信号，执行初次检查与请求
    LaunchedEffect(owner) { // Key 使用 Unit，配合外层的 if (!trigger) return，确保只在组件进入组合且 satisfied 时运行一次
        if (!hasAutoRequested && !context.hasPermission()) {
            logD(tag) { "触发条件满足，正在请求权限..." }
            requestTime = System.currentTimeMillis()
            // 标记位设为 true，下次重建 Activity 时这里依然是 true
            hasAutoRequested = true
            notificationPermissionLauncher.launch(permission)
        }
    }

    // 4. 显示解释弹窗 UI
    if (showRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showRationaleDialog = false; onFinished(false) },
            title = { Text(requestTitle) },
            text = { Text(requestText) },
            confirmButton = {
                TextButton(onClick = {
                    showRationaleDialog = false
                    awaitingSettingsResult = true
                    context.toPermissionSetting(permission)
                }) {
                    Text(stringResource(R.string.base_general_to_authorize_again))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRationaleDialog = false
                    onFinished(false)
                }) {
                    Text(stringResource(com.cla.clip.base.general.R.string.base_general_cancel))
                }
            }
        )
    }
}