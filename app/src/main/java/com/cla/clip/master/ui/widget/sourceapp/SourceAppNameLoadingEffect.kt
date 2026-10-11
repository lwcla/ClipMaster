package com.cla.clip.master.ui.widget.sourceapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cla.clip.master.sourceapp.SourceAppLoadGate

/** 页面聚合一次可见引用；只门控分页/详情历史名称读取，不触发图标加载。 */
@Composable
internal fun SourceAppNameLoadingEffect(
    /** 当前 ViewModel 的加载门控。 */
    gate: SourceAppLoadGate,
    /** 首页 Pager 等保留组合场景的实际可见标记，仍须同时满足 STARTED。 */
    visibleToUser: Boolean = true,
) {
    /** 页面导航目的地的生命周期，不以 Activity 是否存在代替页面可见性。 */
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(gate, lifecycle, visibleToUser) {
        /** 本处组合绑定的独立引用身份。 */
        val owner = Any()
        /** 监听每次生命周期变化，两态都传递以取消隐藏中的读取。 */
        val observer = LifecycleEventObserver { _, _ ->
            gate.setActive(owner, visibleToUser && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        lifecycle.addObserver(observer)
        // 已处于 STARTED 的首次组合也要立即激活，不能等下一次事件。
        gate.setActive(owner, visibleToUser && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycle.removeObserver(observer)
            gate.setActive(owner, false)
        }
    }
}
