package com.cla.clip.master

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.cla.clip.master.ui.screen.main.MainScreen
import com.cla.clip.master.ui.theme.LwlDemoTheme
import com.cla.clip.master.ui.widget.ShizukuServiceUnavailableTip
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LwlDemoTheme {
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
}


