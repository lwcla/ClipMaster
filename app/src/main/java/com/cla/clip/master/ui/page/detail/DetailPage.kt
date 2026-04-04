package com.cla.clip.master.ui.page.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DetailPage(
    clipId: Long,
    onBack: () -> Unit
) {

    // clipId 是从导航参数传递过来的
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("clipId: $clipId")
        // 显示详情内容
    }
}