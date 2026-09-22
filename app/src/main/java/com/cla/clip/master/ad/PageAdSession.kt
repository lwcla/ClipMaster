package com.cla.clip.master.ad

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logW
import com.cla.clip.feature.ad.api.*
import java.util.UUID

/** 页面会话仅保存随机身份、交互与驻留账本，不保存业务实体。 */
@Stable
internal class PageAdSession(
    /** 页面当前是否处于可请求生命周期。 */
    active: Boolean,
) {
    /** 前后台与资格变化只更新活跃状态，不能清空阶段请求历史。 */
    var active by mutableStateOf(active)
        internal set
    /** 每个可见会话随机身份，不能反推剪贴或任务内容。 */
    val nonce: String = UUID.randomUUID().toString()
    /** 真实广告对象驻留和重复请求规则。 */
    private val leases = AdPageLeasePolicy()
    /** 驻留快照使被淘汰广告自动退出组合树。 */
    var residentKeys by mutableStateOf<Set<String>>(emptySet())
        private set
    /** 可见状态和驻留变化驱动尚未取得容量的槽重新尝试，不解除已请求位置保护。 */
    var leaseRevision by mutableIntStateOf(0)
        private set
    /** 由列表或页面显式提供的滚动/动画安全状态。 */
    var canMutateLayout by mutableStateOf(true)
    /** 弹窗、管理多选和凭据输入会立即取消广告资格。 */
    var suppressed by mutableStateOf(false)
    /** 手指按下期间禁止广告布局变更。 */
    var pointerPressed by mutableStateOf(false)
    /** 跨层页面可以提供实时指针读取，避免将按压快照逐层传递产生一帧延迟。 */
    var pointerPressedNow: () -> Boolean = { pointerPressed }
    /** SDK 发布视图之前同步保存页面锚点。 */
    var onLayoutWillChange: (() -> Unit)? = null
    /** SDK 发布视图之后请求下一帧恢复页面锚点。 */
    var onLayoutChanged: (() -> Unit)? = null

    /** 尝试取得位置租约；超过驻留上限淘汰最早位置，不重置历史请求。 */
    fun acquire(key: String): Boolean {
        // 成功意味着允许本会话首次加载，重复位置只复用已有租约。
        val acquired = leases.acquire(key)
        residentKeys = leases.residentKeys
        if (acquired) leaseRevision++
        return acquired
    }
    /** 位置退出或失败后释放驻留计数，不允许同一身份重试。 */
    fun release(key: String) {
        // 没有驻留对象时不发布新版本，避免布局清理形成重组循环。
        val wasResident = key in residentKeys
        leases.release(key)
        residentKeys = leases.residentKeys
        if (wasResident) leaseRevision++
    }
    /** View 实际进入/离开窗口时更新内存淘汰顺序，不输出任何业务坐标。 */
    fun updateVisibility(key: String, visible: Boolean) {
        if (leases.updateVisibility(key, visible)) leaseRevision++
    }
    /** 页面退出时释放全部租约，迟到回调由 adapter 自行销毁。 */
    fun clear() {
        leases.clear()
        residentKeys = emptySet()
    }
}

/** 可见周期或业务 key 改变时创建会话；普通重组和分页追加不重建。 */
@Composable
internal fun rememberPageAdSession(key: Any?, enabled: Boolean = true): PageAdSession {
    // Navigation back stack 生命周期阻止后台页面持有真实广告。
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // 导航可见生命周期与页面业务资格共同决定请求许可。
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    // 只有可交互前台允许请求或持有原生素材。
    val active = enabled && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    // 同一业务 key 的禁止状态或后台切换不能制造新的广告请求身份。
    val session = remember(key) { PageAdSession(active) }
    SideEffect { session.active = active }
    DisposableEffect(session) { onDispose { session.clear() } }
    return session
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
) {
    // 缺失宿主环境时 Preview 和无广告接入保持零尺寸。
    val environment = LocalAdHostEnvironment.current ?: return
    // 仅位置序号进入请求身份，业务锚点和原始内容永不传出。
    val leaseKey = "${placement.placementId}:$slotIndex"
    // 运行中禁止状态立即撤销资格，不等待下一次网络回调。
    val allowed = session.active && eligible && !session.suppressed
    // 选择器只接收资格布尔值，不接收业务内容。
    val selection = environment.selector.select(environment.sources, placement, environment.activeSourceId,
        environment.legacyConsent, environment.policy.copy(isSensitiveContext = !eligible))
    // 当前可用来源；不可用时保持零高度。
    val source = (selection as? AdSourceSelection.Selected)?.source
    // 同一 slot 只申请一次；更换配置、失败或资源淘汰不隐式重试。
    var acquired by remember(session, leaseKey) { mutableStateOf(false) }
    // 首次请求绑定来源；保险丝或设置切换只能释放，不能绕过一次请求规则。
    var acquiredSourceId by remember(session, leaseKey) { mutableStateOf<String?>(null) }
    // 已展示尺寸只用于按下期间安全收起；加载失败从不创建空白。
    var lastHeightPx by remember(session, leaseKey) { mutableIntStateOf(0) }
    // 保存像素高度时使用当前窗口密度转换为 Compose 尺寸。
    val density = LocalDensity.current
    // 宿主窗口尺寸用于判断真实广告是否离开视口；加载中的槽默认离屏。
    val hostView = LocalView.current
    // 回调在同一帧也读取最新指针状态，避免等待重组期间使用过期的展示许可。
    val latestCanReveal by rememberUpdatedState(canReveal)
    LaunchedEffect(session, leaseKey, source?.sourceId, allowed, requestEnabled, session.leaseRevision) {
        if (allowed && requestEnabled && source != null && !acquired) {
            acquired = session.acquire(leaseKey)
            if (acquired) acquiredSourceId = source.sourceId
        }
        if (!allowed || source == null) session.release(leaseKey)
    }
    DisposableEffect(session, leaseKey) { onDispose { session.release(leaseKey) } }
    // 隐私撤销立即释放真实 View，手指尚按下时才临时保留等高空容器。
    val mounted = allowed && acquired && leaseKey in session.residentKeys && source != null && source.sourceId == acquiredSourceId
    if (mounted) {
        key(session.nonce, leaseKey, source.sourceId) {
            source.NativeAdSlot(
                request = AdSlotRequest(
                    placement = placement,
                    requestNonce = "${session.nonce}:$leaseKey",
                    isDebugRequest = environment.policy.debugMode,
                    canReveal = canReveal && session.canMutateLayout && !session.pointerPressed,
                    canRevealNow = { latestCanReveal && session.canMutateLayout && !session.pointerPressedNow() },
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
                        session.release(leaseKey)
                    }
                },
                modifier = modifier
                    .onSizeChanged { size -> if (size.height > 0) lastHeightPx = size.height }
                    .onGloballyPositioned { coordinates ->
                        // SDK View 的实际窗口交集决定可见性，不能用列表预取组合代替曝光范围。
                        val bounds = coordinates.boundsInWindow()
                        // 根窗口内的有效显示范围，不记录坐标或设备尺寸。
                        val viewport = Rect(0f, 0f, hostView.width.toFloat(), hostView.height.toFloat())
                        session.updateVisibility(leaseKey, bounds.width > 0 && bounds.height > 0 && bounds.overlaps(viewport))
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
