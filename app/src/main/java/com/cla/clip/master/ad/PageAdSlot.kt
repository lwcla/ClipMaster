package com.cla.clip.master.ad

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logW
import com.cla.clip.feature.ad.api.*

/** 跨页面广告宿主；只把稳定低敏请求和安全展示许可传给来源。 */
@Composable
internal fun PageAdSlot(
    session: PageAdSession,
    placement: AdPlacement,
    slotIndex: Int = 0,
    eligible: Boolean = true,
    requestEnabled: Boolean = true,
    canReveal: Boolean = true,
    modifier: Modifier = Modifier,
    /** Lazy 槽离屏交给页面缓存；固定槽主动移除时可立即释放。 */
    retainOnDispose: Boolean = true,
) {
    // 缺失宿主环境时 Preview 和无广告接入保持零尺寸。
    val environment = LocalAdHostEnvironment.current ?: return
    // 仅位置序号进入请求身份，业务锚点和原始内容永不传出。
    val leaseKey = "${placement.placementId}:$slotIndex"
    // 组合身份独立于缓存资源，旧 Lazy 回调不能覆盖新挂载的可见状态。
    val binding = remember(session, leaseKey) { Any() }
    // 真实业务资格与临时遮挡分开；暂停/弹窗不撤销资源存活资格。
    val allowed = session.active && eligible
    // 选择器只接收资格布尔值，不接收业务内容。
    val selection = environment.selector.select(environment.sources, placement, environment.activeSourceId,
        environment.legacyConsent, environment.policy.copy(isSensitiveContext = !eligible))
    // 当前可用来源；不可用时保持零高度。
    val source = (selection as? AdSourceSelection.Selected)?.source
    // 当前绑定领取的资源身份；离屏由页面保留，失败/淘汰不在本绑定内循环申请。
    var claimed by remember(session, leaseKey) { mutableStateOf<PageAdSlotLease?>(null) }
    // 记录当前绑定已申请的来源；同来源失败由账本保护，来源变化需重新检查所有门禁。
    var acquiredSourceId by remember(session, leaseKey) { mutableStateOf<String?>(null) }
    // 已展示尺寸只用于按下期间安全收起；加载失败从不创建空白。
    var lastHeightPx by remember(session, leaseKey) { mutableIntStateOf(0) }
    // 保存像素高度时使用当前窗口密度转换为 Compose 尺寸。
    val density = LocalDensity.current
    // 宿主窗口尺寸用于判断真实广告是否离开视口；加载中的槽默认离屏。
    val hostView = LocalView.current
    // 回调在同一帧也读取最新指针状态，避免等待重组期间使用过期的展示许可。
    val latestCanReveal by rememberUpdatedState(canReveal)
    // 容量变化只唤醒未取得租约的位置，已淘汰位置等下次挂载再请求。
    LaunchedEffect(session, leaseKey, source?.sourceId, allowed, requestEnabled,
        session.canRequest, session.suppressed, session.leaseRevision) {
        if (!allowed || source == null || (claimed != null && claimed?.sourceId != source.sourceId)) {
            // 只在硬资格撤销时销毁；普通遮挡不会进入本分支。
            claimed?.let { session.releaseSlot(leaseKey, it,
                if (!eligible) AdReleaseReason.Ineligible else AdReleaseReason.SourceUnavailable) }
            claimed = null
            acquiredSourceId = null
        }
        if (allowed && source != null && acquiredSourceId != source.sourceId) {
            // 缓存回绑不属于新请求；即使暂停/遮挡或只剩广告尾部可见，也保持原布局。
            claimed = session.acquireSlot(leaseKey, source.sourceId, allowNewRequest = requestEnabled)
            if (claimed != null) acquiredSourceId = source.sourceId
        }
        // 必须先登记新组合，再让下一帧创建/搬移 SDK View。
        claimed?.let { session.bindSlot(leaseKey, it, binding) }
    }
    // 离屏解绑不清空资源，旧 onDispose 由请求身份隔离。
    DisposableEffect(session, leaseKey) {
        onDispose {
            claimed?.let {
                if (retainOnDispose) session.detachSlot(leaseKey, it, binding)
                else session.releaseSlot(leaseKey, it, AdReleaseReason.Ineligible)
            }
        }
    }
    // 隐私撤销立即释放真实 View，手指尚按下时才临时保留等高空容器。
    val lease = claimed
    // 读取驻留快照以观察淘汰；所有权校验防止旧回调影响同名槽。
    val mounted = allowed && lease != null && leaseKey in session.residentKeys && session.ownsSlot(leaseKey, lease) &&
        source != null && source.sourceId == acquiredSourceId
    if (mounted) {
        key(lease.nonce, source.sourceId) {
            source.NativeAdSlot(
                request = AdSlotRequest(
                    placement = placement,
                    requestNonce = lease.nonce,
                    isDebugRequest = environment.policy.debugMode,
                    canReveal = canReveal && session.canRequest && !session.suppressed && session.canMutateLayout && !session.pointerPressed,
                    canRevealNow = { latestCanReveal && session.canRequest && !session.suppressed && session.canMutateLayout && !session.pointerPressedNow() },
                    resourceOwner = lease.resources,
                    interactionEnabled = session.canRequest && !session.suppressed,
                    canRequest = requestEnabled && session.canRequest && !session.suppressed,
                    onLayoutWillChange = { session.onLayoutWillChange?.invoke() },
                    onLayoutChanged = { session.onLayoutChanged?.invoke() },
                ),
                onEvent = { event ->
                    // 事件字段只来自封闭低敏契约，禁止输出 SDK 异常原文。
                    if (event.eventType == AdSlotEventType.InitializationFailed || event.eventType == AdSlotEventType.RenderFailed) {
                        environment.disableSource(event.providerId)
                        logW("广告宿主") { "广告来源禁用 source=${event.providerId} placement=${event.placementId} reasonCode=${event.reasonCode}" }
                    } else {
                        logD("广告宿主") { "广告阶段 source=${event.providerId} placement=${event.placementId} event=${event.eventType.eventCode} reasonCode=${event.reasonCode} durationMs=${event.durationMs ?: -1} sdkVersion=${event.sdkVersion}" }
                    }
                    if (event.eventType == AdSlotEventType.NoFill || event.eventType == AdSlotEventType.LoadFailed) {
                        session.releaseSlot(leaseKey, lease, AdReleaseReason.LoadFailed)
                    } else if (event.eventType == AdSlotEventType.Released) {
                        // SDK 可在离屏时撤销隐私或过期，同步清除页面所有权。
                        val reason = AdReleaseReason.entries.firstOrNull { it.code == event.reasonCode } ?: AdReleaseReason.PageLeft
                        session.releaseSlot(leaseKey, lease, reason)
                    }
                },
                modifier = modifier
                    .onSizeChanged { size -> if (size.height > 0) lastHeightPx = size.height }
                    .onGloballyPositioned { coordinates ->
                        // SDK View 的实际窗口交集决定可见性，不能用列表预取组合代替曝光范围。
                        val bounds = coordinates.boundsInWindow()
                        // 根窗口内的有效显示范围，不记录坐标或设备尺寸。
                        val viewport = Rect(0f, 0f, hostView.width.toFloat(), hostView.height.toFloat())
                        session.updateSlotVisibility(leaseKey, lease, binding,
                            bounds.width > 0 && bounds.height > 0 && bounds.overlaps(viewport))
                    },
            )
        }
    } else if (lastHeightPx > 0) {
        // 先销毁真实 View 并保留等高外壳；锚点回调必须发生在清空高度之前。
        Box(modifier.height(with(density) { lastHeightPx.toDp() }))
        LaunchedEffect(lastHeightPx, session.pointerPressed, session.canMutateLayout) {
            if (!session.pointerPressed && session.canMutateLayout) {
                session.onLayoutWillChange?.invoke()
                lastHeightPx = 0
                session.onLayoutChanged?.invoke()
            }
        }
    }
}
