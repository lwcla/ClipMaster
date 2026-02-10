package com.cla.clip.master

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.cla.clip.master.ui.screen.main.MainScreen
import com.cla.clip.master.ui.theme.LwlDemoTheme
import com.cla.clip.master.ui.widget.ShizukuServiceUnavailableTip
import dagger.hilt.android.AndroidEntryPoint
import androidx.core.net.toUri

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LwlDemoTheme {
                val context = LocalContext.current
                // ✅ 修改：增加 savedInstanceState == null 判断
                // 只有当 savedInstanceState 为 null 时，才表示这是 App 的“冷启动”
                // 如果是旋转屏幕或切换深色模式带来的重建，savedInstanceState 不为空
                if (savedInstanceState == null) {
                    LaunchedEffect(Unit) {
                        requestIgnoreBatteryOptimizations(context)
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                ) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding)) {
                        ShizukuServiceUnavailableTip()
                        MainScreen()
                    }
                }
            }
        }
    }

    /** 请求用户将应用添加到电池优化的白名单中，以确保后台服务能够持续运行。 */
    @SuppressLint("BatteryLife") // 忽略 Lint 警告，因为我们确实需要这个权限
    private fun requestIgnoreBatteryOptimizations(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = context.packageName

        // 检查是否已经在白名单中
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                // 弹出系统对话框，请求用户允许
                val intent = Intent().apply {
                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = "package:$packageName".toUri()
                    // 在非 Activity 上下文中启动 activity 需要此 flag，但在 Activity 中其实不需要
                    // addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
                // 如果设备不支持该 Intent，可以引导用户去设置页面手动开启
            }
        }
    }
}


