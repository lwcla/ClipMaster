package com.cla.clip.master.ui.widget

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cla.clip.base.shizuku.ShizukuStatus
import com.cla.clip.base.shizuku.ShizukuUtils
import com.cla.clip.master.R
import com.cla.clip.master.ui.theme.LwlDemoTheme
import rikka.shizuku.Shizuku

/** shizuku 服务不可用提示 */
@Composable
fun ShizukuServiceUnavailableTip() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current

    // 1. 使用 remember 将 status 转换为可变状态，这样修改它时会触发 UI 重组
    var status by remember { mutableStateOf(ShizukuUtils.checkStatus(context)) }


    Log.i("shizuku", "ShizukuServiceUnavailableTip: shizuku状态 $status")

    // 2. 使用 DisposableEffect 监听生命周期
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 在 onResume 时检查 Shizuku 状态
                val new = ShizukuUtils.checkStatus(context)
                Log.i("shizuku", "ShizukuServiceUnavailableTip: ON_RESUME shizuku状态 $new")
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
            stringResource(id = R.string.host_shizuku_not_install) to stringResource(com.cla.clip.base.general.R.string.base_general_to_install)
        }

        is ShizukuStatus.Disconnect.ServiceNotAlive -> {
            stringResource(id = R.string.host_shizuku_service_not_alive) to stringResource(com.cla.clip.base.general.R.string.base_general_to_activate)
        }

        is ShizukuStatus.Disconnect.VersionTooLow -> {
            stringResource(id = R.string.host_shizuku_version_too_low) to stringResource(com.cla.clip.base.general.R.string.base_general_to_update)
        }

        is ShizukuStatus.Disconnect.NotGranted -> {
            stringResource(id = R.string.host_shizuku_not_granted) to stringResource(com.cla.clip.base.general.R.string.base_general_to_authorize)
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clickable(true, onClick = {
                Log.d("shizuku", "ShizukuUtils toConnect: 去连接 Shizuku，当前状态：$status")
                when (status) {
                    is ShizukuStatus.Connected -> {
                        // 已经处于连接状态
                        Log.i("shizuku", "ShizukuUtils toConnect: 已经连接，无需操作")
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
    ) {
        Text(
            text = stringResource(R.string.host_shizuku_service_require),
            fontWeight = FontWeight.Normal,
            color = Color.Red,
            maxLines = 1,
            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE) // 添加跑马灯效果
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp), // 所有子元素间隔 8dp
        ) {
            Text(
                text = tip.first,
                fontWeight = FontWeight.Bold,
                color = Color.Red,
                modifier = Modifier.weight(1f) // 让文字占据剩余空间，把开关挤到右边
            )

            Text(tip.second.plus(">>"))
        }
    }
}


@Preview(showBackground = true)
@Composable
fun ShizukuServiceUnavailableTipPreview() {
    LwlDemoTheme {
        ShizukuServiceUnavailableTip()
    }
}