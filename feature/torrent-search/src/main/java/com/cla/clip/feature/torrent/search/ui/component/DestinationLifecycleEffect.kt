package com.cla.clip.feature.torrent.search.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** 把当前 NavBackStackEntry 生命周期映射为可复用页面活跃回调。 */
@Composable
internal fun DestinationLifecycleEffect(
    /** true 表示当前目的地至少 STARTED，false 表示离页或 Composition 释放。 */
    onActiveChanged: (Boolean) -> Unit,
) {
    /** lifecycleOwner 是当前 NavBackStackEntry，而不是始终存在的 Activity 根。 */
    val lifecycleOwner = LocalLifecycleOwner.current
    /** currentOnActiveChanged 保持最新回调但不因方法引用变化重装生命周期观察者。 */
    val currentOnActiveChanged by rememberUpdatedState(onActiveChanged)
    /** 观察者随目的地 Composition 安装和释放，离页时确保最后发送 false。 */
    DisposableEffect(lifecycleOwner) {
        /** observer 只把 START/STOP 映射为业务网络许可，不把控件状态当生命周期。 */
        val observer = LifecycleEventObserver { /** source 仅满足签名，event 是目的地生命周期事件。 */ _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> currentOnActiveChanged(true)
                Lifecycle.Event.ON_STOP -> currentOnActiveChanged(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) currentOnActiveChanged(true)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            currentOnActiveChanged(false)
        }
    }
}
