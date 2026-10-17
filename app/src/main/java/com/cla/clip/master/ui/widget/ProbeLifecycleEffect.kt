package com.cla.clip.master.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** 网页探测只随页面 STARTED 生命周期运行；暂停/退出关闭会话，后台下载不使用此组件。 */
@Composable
internal fun ProbeLifecycleEffect(onStart: () -> Unit, onStop: () -> Unit) {
    // 生命周期变化不重建业务会话；回调始终指向当前页面的状态控制器。
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val start = rememberUpdatedState(onStart)
    // 始终调用最新暂停入口，生命周期观察器无需因重组重建。
    val stop = rememberUpdatedState(onStop)
    // 注册即同步当前可见性，退出时先关闭回调，再交给 AndroidView 释放实际 WebView。
    DisposableEffect(lifecycle) {
        // START/STOP 分别启动和暂停，旋转与返回复用同一幂等边界。
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start.value()
                Lifecycle.Event.ON_STOP -> stop.value()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start.value() else stop.value()
        onDispose {
            lifecycle.removeObserver(observer)
            stop.value()
        }
    }
}
