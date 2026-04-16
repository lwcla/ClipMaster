package com.cla.clip.master.ui.page.mine

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cla.clip.master.ui.navigation.Route

/** 我的页面 */
@Composable
fun MinePage(
    onNavigate: (route: Route) -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Text(text = "这是我的页面")
    }
}