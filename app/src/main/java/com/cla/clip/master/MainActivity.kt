package com.cla.clip.master

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    private var pendingClipId by mutableStateOf<Long?>(null)
    private var pendingTaskId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingClipId = intent.extractClipId()
        pendingTaskId = intent.extractTaskId()
        enableEdgeToEdge()

        setContent {
            ClipMaterTheme {
                val navController = rememberNavController()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        LaunchedEffect(pendingClipId) {
                            pendingClipId?.let { id ->
                                logI(TAG) { "跳转到详情页 id=$id" }
                                navController.navigate(DetailRoute(id)) { launchSingleTop = true }
                            }
                            pendingClipId = null
                        }

                        LaunchedEffect(pendingTaskId) {
                            pendingTaskId?.let { id ->
                                logI(TAG) { "onCreate: 跳转到下载结果页 id=$id" }
                                navController.navigate(VideoDownloadRoute(id)) { launchSingleTop = true }
                            }
                            pendingTaskId = null
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
        pendingClipId = intent.extractClipId()
        pendingTaskId = intent.extractTaskId()
        logI(TAG) { "onNewIntent: pendingClipId=$pendingClipId pendingTaskId=$pendingTaskId" }
    }
}


