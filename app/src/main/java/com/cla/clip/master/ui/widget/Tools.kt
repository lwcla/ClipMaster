package com.cla.clip.master.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.utils.toRelativeTimeSpanString

/**
 * 在 Compose 中记住剪贴板记录的相对时间文案。
 *
 * 页面处于 STARTED 生命周期时会重新计算一次，避免列表长时间停留后“刚刚/几分钟前”等展示过期；返回值只用于 UI 展示。
 */
@Composable
fun ClipShowEntity.rememberFormattedTime(): String {
    var formattedTime by remember { mutableStateOf(formattedTime) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(timestamp, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            formattedTime = timestamp.toRelativeTimeSpanString()
        }
    }

    return formattedTime
}
