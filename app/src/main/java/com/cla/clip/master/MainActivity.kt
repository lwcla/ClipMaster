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
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.cla.clip.base.general.utils.logI
import com.cla.clip.master.ui.navigation.AppNavigation
import com.cla.clip.master.ui.navigation.DetailRoute
import com.cla.clip.master.ui.navigation.VideoDownloadRoute
import com.cla.clip.master.ui.theme.ClipMaterTheme
import com.cla.clip.master.utils.ClipHelper
import com.cla.clip.master.utils.NotificationHelper.Companion.extractClipId
import com.cla.clip.master.utils.NotificationHelper.Companion.extractTaskId
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    @Inject
    lateinit var clipHelper: dagger.Lazy<ClipHelper>

    private val mainVm by viewModels<MainVm>()

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainVm.pendingClipId = intent.extractClipId()
        mainVm.pendingTaskId = intent.extractTaskId()
        logI(TAG) { "onCreate: pendingClipId=${mainVm.pendingClipId} pendingTaskId=${mainVm.pendingTaskId}" }
        enableEdgeToEdge()

        setContent {
            ClipMaterTheme {
                val navController = rememberNavController()

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

                        AppNavigation(navController)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        clipHelper.get().readNow(resume = true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        clipHelper.get().readNow(hasFocus = hasFocus)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        mainVm.pendingClipId = intent.extractClipId()
        mainVm.pendingTaskId = intent.extractTaskId()
        logI(TAG) { "onNewIntent: pendingClipId=${mainVm.pendingClipId} pendingTaskId=${mainVm.pendingTaskId}" }
    }
}


