package com.cla.clip.master

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.cla.clip.base.general.R
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.toast
import com.cla.clip.master.ui.navigation.AppNavigation
import com.cla.clip.master.ui.navigation.DetailRoute
import com.cla.clip.master.ui.navigation.VideoDownloadRoute
import com.cla.clip.master.ui.theme.ClipMaterTheme
import com.cla.clip.master.utils.ClipHelper
import com.cla.clip.master.utils.ImageFolderOpenHelper
import com.cla.clip.master.utils.ImageFolderOpenHelper.ImageFolderOpenResult
import com.cla.clip.master.utils.NotificationHelper.Companion.extractClipId
import com.cla.clip.master.utils.NotificationHelper.Companion.extractImageFolderOpenData
import com.cla.clip.master.utils.NotificationHelper.Companion.extractTaskId
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
/**
 * 应用主 Activity。
 *
 * 承载 Compose 导航宿主，处理通知点击带来的剪贴板详情页、视频下载页和图片保存目录打开动作，并在前台/获焦时触发一次剪贴板读取。
 * 通知参数交给 MainVm 做一次性消费，避免 Activity 重组或重复 intent 导致页面重复打开。
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** 主 Activity 日志标签，用于定位通知跳转和剪贴板读取触发时机。 */
        private const val TAG = "MainActivity"
    }

    /** 剪贴板读取助手，使用 Lazy 避免 Activity 创建时立即触发较重的依赖初始化。 */
    @Inject
    lateinit var clipHelper: dagger.Lazy<ClipHelper>

    /** Activity 级 ViewModel，保存来自通知 intent 的一次性跳转目标。 */
    private val mainVm by viewModels<MainVm>()

    /**
     * 初始化 Compose 内容和导航。
     *
     * 启动时先读取通知参数，再通过 LaunchedEffect 消费 pending id；这样既能支持冷启动通知跳转，
     * 也能避免普通重组重复导航。
     */
    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainVm.pendingClipId = intent.extractClipId()
        mainVm.pendingTaskId = intent.extractTaskId()
        mainVm.pendingImageFolderOpenData = intent.extractImageFolderOpenData()
        logI(TAG) {
            "onCreate: pendingClipId=${mainVm.pendingClipId} pendingTaskId=${mainVm.pendingTaskId} pendingImageFolderOpenData=${mainVm.pendingImageFolderOpenData}"
        }
        enableEdgeToEdge()

        setContent {
            ClipMaterTheme {
                val navController = rememberNavController()
                /** 图片通知打开目录失败时需要从 Compose 侧发起 Toast 协程，避免在 LaunchedEffect 中阻塞 UI。 */
                val coroutineScope = rememberCoroutineScope()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LaunchedEffect(mainVm.pendingClipId) {
                            mainVm.pendingClipId()?.let { id ->
                                logI(TAG) { "跳转到详情页 id=$id" }
                                navController.navigate(DetailRoute(id)) { launchSingleTop = true }
                            }
                        }

                        LaunchedEffect(mainVm.pendingTaskId) {
                            mainVm.pendingTaskId()?.let { id ->
                                logI(TAG) { "onCreate: 跳转到下载结果页 id=$id" }
                                navController.navigate(VideoDownloadRoute(id)) { launchSingleTop = true }
                            }
                        }

                        LaunchedEffect(mainVm.pendingImageFolderOpenData) {
                            mainVm.pendingImageFolderOpenData()?.let { data ->
                                logI(TAG) { "onCreate: 打开图片相册 outputDir=${data.outputDir}" }
                                when (ImageFolderOpenHelper.openDownloadedImageFolder(this@MainActivity, data.outputDir)) {
                                    ImageFolderOpenResult.Gallery -> {
                                        Unit
                                    }

                                    ImageFolderOpenResult.None -> {
                                        coroutineScope.launch {
                                            this@MainActivity.toast(R.string.base_general_no_available_app_to_open_image_folder)
                                        }
                                    }
                                }
                            }
                        }

                        AppNavigation(navController)
                    }
                }
            }
        }
    }

    /** Activity 回到前台时主动读取一次剪贴板，补偿系统剪贴板监听可能丢失的情况。 */
    override fun onResume() {
        super.onResume()
        clipHelper.get().readNow(resume = true)
    }

    /** 窗口重新获得焦点时读取剪贴板，覆盖用户从其他应用复制后回到本应用的常见路径。 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        clipHelper.get().readNow(hasFocus = hasFocus)
    }

    /**
     * 处理已存在 Activity 收到的新通知 intent。
     *
     * 需要调用 setIntent 更新 Activity 当前 intent，并刷新 MainVm 的一次性跳转目标，让 LaunchedEffect 能继续处理新目标。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        mainVm.pendingClipId = intent.extractClipId()
        mainVm.pendingTaskId = intent.extractTaskId()
        mainVm.pendingImageFolderOpenData = intent.extractImageFolderOpenData()
        logI(TAG) {
            "onNewIntent: pendingClipId=${mainVm.pendingClipId} pendingTaskId=${mainVm.pendingTaskId} pendingImageFolderOpenData=${mainVm.pendingImageFolderOpenData}"
        }
    }
}
