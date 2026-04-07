package com.cla.clip.master

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
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
import com.cla.clip.master.ui.theme.ClipMaterTheme
import com.cla.clip.master.ui.widget.ShizukuServiceUnavailableTip
import com.cla.clip.master.utils.NotificationHelper.Companion.extractClipId
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var pendingClipId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingClipId = intent.extractClipId()
        enableEdgeToEdge()

        setContent {
            ClipMaterTheme {
                val navController = rememberNavController()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                ) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding)) {
                        ShizukuServiceUnavailableTip()

                        LaunchedEffect(pendingClipId) {
                            pendingClipId?.let { id ->
                                logI(TAG) { "跳转到详情页 id=$id" }
                                navController.navigate(DetailRoute(id)) { launchSingleTop = true }
                            }
                            pendingClipId = null
                        }

                        AppNavigation(navController)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingClipId = intent.extractClipId()
    }
}


