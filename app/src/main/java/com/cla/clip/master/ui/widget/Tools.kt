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

/**
 * 在 Compose 中记住回收站删除时间的相对展示文案。
 *
 * 回收站按删除时间排序，如果继续展示原剪贴时间会让用户误解排序；这里使用 `deletedAt` 生成“删除于 X”的动态文案。
 */
@Composable
fun ClipShowEntity.rememberDeletedFormattedTime(prefix: String): String {
    var formattedTime by remember { mutableStateOf("$prefix${deletedAt.toRelativeTimeSpanString()}") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(deletedAt, prefix, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            formattedTime = "$prefix${deletedAt.toRelativeTimeSpanString()}"
        }
    }

    return formattedTime
}
