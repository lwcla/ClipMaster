package com.cla.clip.feature.ad.admob

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cla.clip.feature.ad.api.*
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import kotlinx.coroutines.*

/** 单个 nonce 的资源所有者；加载、超时、隐私撤回和释放都在主线程串行处理。 */
internal class AdmobSlotSession(
    /** 只在本次页面存活期使用的 UI Context。 */
    context: Context,
    /** 不包含用户正文的初始请求。 */
    request: AdSlotRequest,
    /** 进程共享初始化器。 */
    private val initializer: AdmobInitializer,
    /** 平台隐私事实来源。 */
    private val privacy: AdmobPrivacyController,
    /** 广告位对应的构建配置。 */
    private val unitId: String,
    /** 低敏事件交给宿主统一诊断。 */
    onEvent: (AdSlotEvent) -> Unit,
) : AdSlotResource {
    /** 页面释放后清空 Context 引用，迟到 SDK 回调不再持有 Activity。 */
    private var uiContext: Context? = context
    /** 当前回调和展示门控，可更新但不参与请求身份。 */
    private var request = request
    /** 回调使用最新版本，避免捕获过期宿主状态。 */
    private var onEvent = onEvent
    /** 页面生命周期作用域，不与进程初始化作用域共享取消。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** 把曝光、点击回调切回主线程。 */
    private val main = Handler(Looper.getMainLooper())
    /** 去重、终态与幂等释放的纯规则。 */
    private val guard = AdmobRequestGuard()
    /** 缓存反复绑定只启动一次网络请求。 */
    private var started = false
    /** 当前组合的身份；旧组合迟到解绑不能撤掉新组合的 View。 */
    private var binding: Any? = null
    /** 素材取得时间使用单调时钟，设备改时间不会延长一小时有效期。 */
    private var loadedAt: Long? = null
    /** 加载完成但尚未允许展示的素材仍由当前请求持有。 */
    private var ad: NativeAd? = null
    /** 当前公开给 Compose 的 View；空值占用零尺寸。 */
    var view: NativeAdView? by mutableStateOf(null)
        private set
    /** 自动隐藏保留 View/素材但清除呈现状态；重新挂载仍要通过滚动和按压安全门。 */
    var presented by mutableStateOf(false)
        private set
    /** 初始化与加载共享十五秒总预算的起点，用于低敏阶段耗时。 */
    private val startedAt = SystemClock.elapsedRealtime()

    /** 仅前台绑定启动；重复恢复或回滚绑定不再次请求。 */
    fun start() {
        if (started || !guard.canAcceptAd || !request.canRequest) return
        started = true
        emit(AdSlotEventType.RequestStarted)
        scope.launch {
            privacy.state.collect { state ->
                if (!state.canRequestAds) close(AdReleaseReason.PrivacyRevoked)
            }
        }
        scope.launch {
            /** 阶段标记用于区分初始化异常和加载异常。 */
            var initializing = true
            try {
                // 容纳实际冷启动及代理延迟；离开页面仍立即取消，不等待预算耗尽。
                withTimeout(15_000L) {
                    if (!privacy.state.value.canRequestAds) {
                        close(AdReleaseReason.PrivacyRevoked)
                        return@withTimeout
                    }
                    initializer.awaitReady()
                    initializing = false
                    if (!privacy.state.value.canRequestAds) {
                        close(AdReleaseReason.PrivacyRevoked)
                        return@withTimeout
                    }
                    /** 加载返回后请求拥有该素材，直到释放或渲染失败。 */
                    val loaded = AdmobSdkLoader.load(unitId, request.placement)
                    if (!guard.finishLoading() || !privacy.state.value.canRequestAds) {
                        loaded.destroy()
                        return@withTimeout
                    }
                    ad = loaded
                    loadedAt = SystemClock.elapsedRealtime()
                    // 缓存离屏时也要过期；协程随页面真正离开或隐私撤销一并取消。
                    scope.launch {
                        delay(60 * 60 * 1000L)
                        close(AdReleaseReason.Expired)
                    }
                    loaded.adEventCallback = object : NativeAdEventCallback {
                        /** SDK 曝光事件不能替代“素材加载完成”。 */
                        override fun onAdImpression() {
                            main.post { emit(AdSlotEventType.Impression) }
                        }
                        /** SDK 点击只用于诊断，宿主不覆盖实际点击。 */
                        override fun onAdClicked() {
                            main.post { emit(AdSlotEventType.Clicked) }
                        }
                    }
                    emit(AdSlotEventType.Loaded)
                    revealIfAllowed()
                }
            } catch (timeout: TimeoutCancellationException) {
                guard.finishLoading()
                emit(AdSlotEventType.LoadFailed, "admob_timeout")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AdmobLoadFailure) {
                guard.finishLoading()
                emit(if (failure.noFill) AdSlotEventType.NoFill else AdSlotEventType.LoadFailed,
                    if (failure.noFill) "admob_no_fill" else "admob_load_failed")
            } catch (failure: Exception) {
                guard.finishLoading()
                emit(if (initializing) AdSlotEventType.InitializationFailed else AdSlotEventType.LoadFailed,
                    if (initializing) "admob_initialization_failed" else "admob_load_failed")
            }
        }
    }

    /** 更新门控不产生请求；一旦展示，滚动开始不再反复移除广告。 */
    fun update(binding: Any, request: AdSlotRequest, onEvent: (AdSlotEvent) -> Unit) {
        // 关闭后不重新捕获页面回调，防止迟到 SideEffect 延长 Activity 引用。
        if (uiContext == null) return
        this.binding = binding
        this.request = request
        this.onEvent = onEvent
        if (!privacy.state.value.canRequestAds) {
            close(AdReleaseReason.PrivacyRevoked)
        } else if (loadedAt?.let { SystemClock.elapsedRealtime() - it >= 60 * 60 * 1000L } == true) {
            close(AdReleaseReason.Expired)
        } else {
            if (!request.contentPresentable || !request.contentPresentableNow()) {
                // 空态与 View 解绑同次主线程更新，不销毁素材，不生成新请求或关闭记录。
                presented = false
                (view?.parent as? ViewGroup)?.removeView(view)
            } else revealIfAllowed()
        }
    }

    /** 登记新绑定与搬移 View 在同一主线程调用内完成，旧 onDispose 不能拆掉新外壳。 */
    fun bindView(binding: Any, container: ViewGroup) {
        if (!request.contentPresentable || !request.contentPresentableNow() || !presented) return
        // 素材已销毁或尚未揭示时没有可搬移的 View。
        val nativeView = view ?: return
        this.binding = binding
        if (nativeView.parent !== container) {
            (nativeView.parent as? ViewGroup)?.removeView(nativeView)
            container.addView(nativeView)
        }
    }

    /** Lazy 离屏仅解绑；保留素材、View 和隐私监听，清除旧布局回调。 */
    fun detach(binding: Any) {
        if (this.binding !== binding) return
        this.binding = null
        presented = false
        // 主线程解绑即移除关闭监听，缓存素材不保留离屏组合的页面引用。
        view?.let { AdmobDismissControl.update(it, false) }
        (view?.parent as? ViewGroup)?.removeView(view)
        request = request.copy(canReveal = false, canRevealNow = { false }, contentPresentable = false, contentPresentableNow = { false },
            onLayoutWillChange = {}, onLayoutChanged = {}, onDismiss = {}, interactionEnabled = false)
    }

    /** 允许展示时才创建原生 View；渲染异常触发独立保险丝事件。 */
    private fun revealIfAllowed() {
        // 布尔快照尚未随重组更新时，实时门仍能同步阻止交互中的布局变化。
        if (binding == null || !request.contentPresentable || !request.contentPresentableNow() || !privacy.state.value.canRequestAds) return
        // 已在窗口的素材不因普通滚动收起；缓存 View 重新挂载要重新取得安全许可。
        if (presented || !request.canReveal || !request.canRevealNow()) return
        /** 当前素材和 Activity 上下文都必须仍然存在。 */
        val currentAd = ad ?: return
        /** 页面关闭后没有合法 UI 宿主。 */
        val context = uiContext ?: return
        try {
            /** 先完成 SDK 绑定，再通知宿主即将改变布局。 */
            val created = view ?: AdmobNativeViewFactory.create(context, currentAd)
            request.onLayoutWillChange()
            view = created
            presented = true
            request.onLayoutChanged()
        } catch (failure: Exception) {
            emit(AdSlotEventType.RenderFailed, "admob_render_failed")
            close(AdReleaseReason.RenderFailed)
        }
    }

    /** 低敏事件按请求去重，禁止携带 adUnitId、SDK 消息或广告素材。 */
    private fun emit(type: AdSlotEventType, reason: String = "") {
        if (!guard.record(type.eventCode)) return
        onEvent(AdSlotEvent(
            providerId = "admob",
            placementId = request.placement.placementId,
            eventType = type,
            reasonCode = reason,
            durationMs = SystemClock.elapsedRealtime() - startedAt,
            isDebug = BuildConfig.DEBUG,
            sdkVersion = "1.4.0",
        ))
    }

    /** 真正离开/过期/撤销才销毁；先关闭状态再回报，防止宿主释放回调重入。 */
    override fun close(reason: AdReleaseReason) {
        if (!guard.close()) return
        presented = false
        scope.cancel()
        /** 当前 View 先撤出布局，锚点回调必须先于尺寸变化。 */
        val previousView = view
        if (previousView != null) {
            request.onLayoutWillChange()
            view = null
            (previousView.parent as? ViewGroup)?.removeView(previousView)
            previousView.destroy()
            request.onLayoutChanged()
        }
        ad?.destroy()
        ad = null
        uiContext = null
        binding = null
        onEvent(AdSlotEvent("admob", request.placement.placementId, AdSlotEventType.Released,
            reason.code, SystemClock.elapsedRealtime() - startedAt, BuildConfig.DEBUG, "1.4.0"))
        // 释放后清除捕获的页面环境及回调，SDK 的迟到结果只能走 guard 拒绝分支。
        onEvent = {}
        request = request.copy(canReveal = false, canRevealNow = { false }, contentPresentable = false, contentPresentableNow = { false },
            onLayoutWillChange = {}, onLayoutChanged = {}, onDismiss = {})
    }
}
