package com.cla.clip.master.ad

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cla.clip.feature.ad.api.AdReleaseReason
import com.cla.clip.feature.ad.api.AdPlacement
import com.cla.clip.feature.ad.api.AdSourceSelection

/** 业务 key 或真实可见访问改变时创建新会话；资格变化、普通重组和分页追加不重建。 */
@Composable
internal fun rememberPageAdSession(
    key: Any?,
    enabled: Boolean = true,
    visibleToUser: Boolean = true,
): PageAdSession {
    /** 导航 entry 生命周期与显式 Tab 可见性共同界定一次页面访问。 */
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    /** 生命周期回调读取最新业务资格，不因捕获旧值跳过请求门禁。 */
    val latestEnabled by rememberUpdatedState(enabled)
    /** Tab 切换不一定改变导航生命周期，因此单独保留最新可见性。 */
    val latestVisible by rememberUpdatedState(visibleToUser)
    /** 生命周期宿主或业务阶段改变即隔离旧所有者，不跨页面/阶段复用请求账本。 */
    val owner = remember(key, lifecycle) {
        PageAdSessionOwner(
            initiallyVisible = visibleToUser && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
            initiallyEnabled = enabled,
            initiallyResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        )
    }
    // 直接观察事件，避免后台停止重组时 StateFlow 合并掉短暂的离开/返回边界。
    DisposableEffect(owner, lifecycle) {
        /** 暂停保留原访问，停止才释放；销毁是终态，不允许迟到恢复。 */
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) owner.close(AdReleaseReason.Destroyed)
            else owner.update(
                latestVisible && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED), latestEnabled,
                resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                releaseReason = if (!latestVisible) AdReleaseReason.TabChanged else AdReleaseReason.LifecycleStop,
            )
        }
        lifecycle.addObserver(observer)
        // 先注销再关闭，迟到事件也不能使已离开组合的页面重新活跃。
        onDispose {
            lifecycle.removeObserver(observer)
            owner.close()
        }
    }
    // Tab 可见性或业务资格变化不一定伴随生命周期事件，需要在提交组合后同步。
    SideEffect {
        owner.update(
            visibleToUser && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED), enabled,
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            releaseReason = if (!visibleToUser) AdReleaseReason.TabChanged else AdReleaseReason.LifecycleStop,
        )
    }
    // 页面层清理所有离屏缓存，不能只依赖已挂载槽的来源选择器。
    val environment = LocalAdHostEnvironment.current
    SideEffect {
        // 仅检查低敏来源资格；敏感正文资格仍由对应槽传入。
        val allowedSources = if (environment == null) emptySet() else AdPlacement.entries.mapNotNull { placement ->
            // 复用选择器的未知来源回退、隐私及保险丝规则，避免缓存层另写一套判断。
            (environment.selector.select(environment.sources, placement, environment.activeSourceId,
                environment.legacyConsent, environment.policy) as? AdSourceSelection.Selected)?.source?.sourceId
        }.toSet()
        owner.session.retainSources(allowedSources)
    }
    return owner.session
}

/** 观察触摸而不消费事件，不改变列表滚动、业务按钮或 SDK 点击分发。 */
internal fun Modifier.adInteractionGuard(session: PageAdSession): Modifier = pointerInput(session) {
    try {
        awaitPointerEventScope {
            while (true) {
                // Initial 阶段先看到按下，避免 SDK 在按钮收到 down 的同帧插入。
                val event = awaitPointerEvent(PointerEventPass.Initial)
                session.pointerPressed = event.changes.any { it.pressed }
            }
        }
    } finally {
        session.pointerPressed = false
    }
}
