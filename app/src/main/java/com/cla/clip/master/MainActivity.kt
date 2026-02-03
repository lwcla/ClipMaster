package com.cla.clip.master

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.cla.clip.master.ui.theme.LwlDemoTheme
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val shizukuVm by viewModels<ShizukuVm>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Shizuku.addRequestPermissionResultListener(shizukuVm.shizukuPermissionListener)

        setContent {
            LwlDemoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding)) {
                        ShizukuServiceUnavailableTip(shizukuVm)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuVm.shizukuPermissionListener)
    }
}

/** shizuku 服务不可用提示 */
@Composable
fun ShizukuServiceUnavailableTip(shizukuVm: ShizukuVm) {
    val shizukuAvailable = shizukuVm.shizukuAvailable.value
    val context = LocalContext.current

    // 1. 定义控制弹窗显示的状态
    // remember 保证在重组时状态不丢失，mutableStateOf 让 Compose 监听变化
    var showInstallDialog by remember { mutableStateOf(false) }

    // 2. 根据状态决定是否渲染弹窗 Composable
    if (showInstallDialog) {
        AlertDialog(
            onDismissRequest = { showInstallDialog = false },
            title = { Text(stringResource(id = R.string.shizuku_not_install)) },
            text = { Text(stringResource(R.string.shizuku_not_install_tip)) },
            confirmButton = {
                TextButton(onClick = {
                    showInstallDialog = false
                    // 跳转到 Shizuku 官网或 GitHub 发布页
                    val intent = Intent(Intent.ACTION_VIEW, "https://shizuku.rikka.app/download/".toUri())
                    context.startActivity(intent)
                }) {
                    Text("去下载")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInstallDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp), // 所有子元素间隔 8dp
        modifier = Modifier.padding(16.dp)
    ) {
        Text(
            text = stringResource(id = R.string.the_shizuku_service_is_unavailable),
            fontWeight = FontWeight.Bold,
            color = Color.Red,
            modifier = Modifier.weight(1f) // 让文字占据剩余空间，把开关挤到右边
        )
        Switch(
            checked = shizukuAvailable,
            onCheckedChange = {
                // 先检查shizuku是否已经安装
                if (!shizukuVm.isShizukuInstalled(context)) {
                    // 提示用户安装 Shizuku
                    Log.i("lwl", "ShizukuVm toConnect: 没有安装 shizuku")
                    showInstallDialog = true
                    return@Switch
                }

                // 去连接 Shizuku 服务
                Log.i("lwl", "ShizukuVm toConnect: 尝试连接 shizuku 服务")
                // 请求权限
//              Shizuku.requestPermission(0)
            }
        )
    }
}


