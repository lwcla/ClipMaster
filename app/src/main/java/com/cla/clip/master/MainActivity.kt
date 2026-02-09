package com.cla.clip.master

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import com.cla.clip.master.ui.screen.main.MainScreen
import com.cla.clip.master.ui.theme.LwlDemoTheme
import com.cla.clip.master.ui.widget.ShizukuServiceUnavailableTip
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LwlDemoTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text("ClipMaster") })
                    },
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
}


