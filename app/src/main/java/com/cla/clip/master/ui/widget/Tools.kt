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
import com.cla.clip.base.general.entity.ClipEntity
import com.cla.clip.base.general.utils.toRelativeTimeSpanString

/** ClipEntity 的扩展函数，用于在 Compose 中记住格式化的时间字符串，并在生命周期内自动更新 */
@Composable
fun ClipEntity.rememberFormattedTime(): String {
    var formattedTime by remember { mutableStateOf(formattedTime) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(timestamp, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            formattedTime = timestamp.toRelativeTimeSpanString()
        }
    }

    return formattedTime
}