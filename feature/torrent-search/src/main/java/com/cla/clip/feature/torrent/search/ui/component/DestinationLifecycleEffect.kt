package com.cla.clip.feature.torrent.search.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    rememberDestinationActiveState(onActiveChanged)
}

/** 把当前 NavBackStackEntry 生命周期直接暴露为 Compose 状态，并同步业务活跃回调。 */
@Composable
internal fun rememberDestinationActiveState(
    /** true 表示当前目的地至少 STARTED，false 表示离页或 Composition 释放。 */
    onActiveChanged: (Boolean) -> Unit,
): State<Boolean> {
    /** lifecycleOwner 是当前 NavBackStackEntry，而不是始终存在的 Activity 根。 */
    val lifecycleOwner = LocalLifecycleOwner.current
    /** currentOnActiveChanged 保持最新回调但不因方法引用变化重装生命周期观察者。 */
    val currentOnActiveChanged by rememberUpdatedState(onActiveChanged)
    /** activeState 由目的地事件直接写入，使媒体 Composition 不依赖暂停收集的 ViewModel StateFlow。 */
    val activeState = remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    /** 观察者随目的地 Composition 安装和释放，离页时确保最后发送 false。 */
    DisposableEffect(lifecycleOwner) {
        /** observer 同步更新 Compose 展示许可与 ViewModel 网络许可。 */
        val observer = LifecycleEventObserver { /** source 仅满足签名，event 是目的地生命周期事件。 */ _, event ->
            /** nextActive 是消费当前生命周期事件后的直接展示许可。 */
            val nextActive = destinationActiveAfterEvent(activeState.value, event)
            if (nextActive != activeState.value) {
                activeState.value = nextActive
                currentOnActiveChanged(nextActive)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        currentOnActiveChanged(activeState.value)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            activeState.value = false
            currentOnActiveChanged(false)
        }
    }
    return activeState
}

/** 将 START/STOP/DESTROY 映射为目的地直接活跃状态，其余事件保持原值。 */
internal fun destinationActiveAfterEvent(
    /** currentActive 是事件到达前的展示许可。 */
    currentActive: Boolean,
    /** event 是当前 NavBackStackEntry 发出的生命周期事件。 */
    event: Lifecycle.Event,
): Boolean = when (event) {
    Lifecycle.Event.ON_START -> true
    Lifecycle.Event.ON_STOP,
    Lifecycle.Event.ON_DESTROY,
    -> false
    else -> currentActive
}
