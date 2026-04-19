package com.cla.clip.master.ui.widget

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cla.clip.base.general.utils.hasNotificationPermission
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logI
import com.cla.clip.master.R
import com.cla.clip.master.ui.page.list.ClipListModel
import com.cla.clip.master.ui.theme.ClipMaterTheme
import com.cla.clip.shizuku.ShizukuStatus
import com.cla.clip.shizuku.ShizukuUtils
import rikka.shizuku.Shizuku

/** shizuku 服务不可用提示 */
@Composable
fun ShizukuServiceUnavailableTip(
    viewModel: ClipListModel = hiltViewModel()
) {
    val tag = "shizuku状态提示"

    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current

    // 1. 使用 remember 将 status 转换为可变状态，这样修改它时会触发 UI 重组
    var status by remember { mutableStateOf(ShizukuUtils.checkStatus(context)) }

    logI(tag) { "shizuku状态 $status" }

    // 2. 使用 DisposableEffect 监听生命周期
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 在 onResume 时检查 Shizuku 状态
                val new = ShizukuUtils.checkStatus(context)
                logI(tag) { "ON_RESUME shizuku状态 $new" }
                // 从后台返回前台时，如果是已经连接shizuku的情况下，尝试绑定shizuku进程，如果已经绑定中，则不作其他操作，如果是已经失活，则再次绑定
                if (new is ShizukuStatus.Connected && context.hasNotificationPermission()) viewModel.connectShizuku()
                status = new
            }
        }

        val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            status = ShizukuUtils.checkStatus(context)
        }

        owner.lifecycle.addObserver(observer)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        }
    }

    // ========================================================
    // 【关键修改】调用分离出来的权限处理组件
    // 只要 status 是 Connected，就触发权限检查逻辑
    // 将它放在 return 之前，确保连接成功后即便 UI 消失，权限逻辑仍能运行一次（如果它还在 Composable 树中）
    // 或者，因为下面 return 了，这个组件会留在 Composition 中（因为它是 return 之前的），
    // 只有 return 之后的组件才会被移除。所以这里逻辑是通的。
    // ========================================================
    HandleNotificationPermission(trigger = status is ShizukuStatus.Connected)

    val tip = when (status) {
        is ShizukuStatus.Connected -> {
            return
        }

        is ShizukuStatus.Disconnect.NotInstalled -> {
            stringResource(id = com.cla.clip.base.general.R.string.base_general_shizuku_not_install) to stringResource(com.cla.clip.base.general.R.string.base_general_to_install)
        }

        is ShizukuStatus.Disconnect.ServiceNotAlive -> {
            stringResource(id = com.cla.clip.base.general.R.string.base_general_shizuku_service_not_alive) to stringResource(com.cla.clip.base.general.R.string.base_general_to_activate)
        }

        is ShizukuStatus.Disconnect.VersionTooLow -> {
            stringResource(id = com.cla.clip.base.general.R.string.base_general_shizuku_version_too_low) to stringResource(com.cla.clip.base.general.R.string.base_general_to_update)
        }

        is ShizukuStatus.Disconnect.NotGranted -> {
            stringResource(id = com.cla.clip.base.general.R.string.base_general_shizuku_not_granted) to stringResource(com.cla.clip.base.general.R.string.base_general_to_authorize)
        }
    }

    Text(
        text = buildAnnotatedString {
            // 第一段：红色
            withStyle(style = SpanStyle(color = Color.Red, fontWeight = FontWeight.Bold)) {
                append(stringResource(R.string.host_shizuku_service_require))
            }

            withStyle(style = SpanStyle(color = Color.Red, fontWeight = FontWeight.Medium)) {
                append("(${tip.first})")
            }

            // 第二段：
            withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Normal)) {
                append("\n${tip.second}>>")
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
                logD(tag) { "去连接 Shizuku，当前状态：$status" }
                when (status) {
                    is ShizukuStatus.Connected -> {
                        // 已经处于连接状态
                        logI(tag) { "toConnect: Shizuku 已经连接，无需操作" }
                    }

                    is ShizukuStatus.Disconnect.NotInstalled,
                    is ShizukuStatus.Disconnect.VersionTooLow -> {
                        // 跳转到下载页面
                        ShizukuUtils.toDownloadApk(context)
                    }

                    is ShizukuStatus.Disconnect.ServiceNotAlive -> {
                        ShizukuUtils.toShizukuApp(context)
                    }

                    else -> {
                        ShizukuUtils.toConnect()
                    }
                }
            })
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}


@Preview(showBackground = true)
@Composable
fun ShizukuServiceUnavailableTipPreview() {
    ClipMaterTheme {
        ShizukuServiceUnavailableTip()
    }
}