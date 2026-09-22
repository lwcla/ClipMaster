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
) {
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
    /** 加载完成但尚未允许展示的素材仍由当前请求持有。 */
    private var ad: NativeAd? = null
    /** 当前公开给 Compose 的 View；空值占用零尺寸。 */
    var view: NativeAdView? by mutableStateOf(null)
        private set
    /** 初始化与加载共享四秒总预算。 */
    private val startedAt = SystemClock.elapsedRealtime()

    /** 只由 DisposableEffect 对本 nonce 调用一次，不因重组重复加载。 */
    fun start() {
        emit(AdSlotEventType.RequestStarted)
        scope.launch {
            privacy.state.collect { state ->
                if (!state.canRequestAds) close()
            }
        }
        scope.launch {
            /** 阶段标记用于区分初始化异常和加载异常。 */
            var initializing = true
            try {
                withTimeout(4_000L) {
                    if (!privacy.state.value.canRequestAds || request.isSensitiveContext) {
                        close()
                        return@withTimeout
                    }
                    initializer.awaitReady()
                    initializing = false
                    if (!privacy.state.value.canRequestAds) {
                        close()
                        return@withTimeout
                    }
                    /** 加载返回后请求拥有该素材，直到释放或渲染失败。 */
                    val loaded = AdmobSdkLoader.load(unitId)
                    if (!guard.finishLoading() || !privacy.state.value.canRequestAds) {
                        loaded.destroy()
                        return@withTimeout
                    }
                    ad = loaded
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
    fun update(request: AdSlotRequest, onEvent: (AdSlotEvent) -> Unit) {
        this.request = request
        this.onEvent = onEvent
        if (request.isSensitiveContext || !privacy.state.value.canRequestAds) close() else revealIfAllowed()
    }

    /** 允许展示时才创建原生 View；渲染异常触发独立保险丝事件。 */
    private fun revealIfAllowed() {
        // 布尔快照尚未随重组更新时，实时门仍能同步阻止交互中的布局变化。
        if (!request.canReveal || !request.canRevealNow() || view != null || !privacy.state.value.canRequestAds) return
        /** 当前素材和 Activity 上下文都必须仍然存在。 */
        val currentAd = ad ?: return
        /** 页面关闭后没有合法 UI 宿主。 */
        val context = uiContext ?: return
        try {
            /** 先完成 SDK 绑定，再通知宿主即将改变布局。 */
            val created = AdmobNativeViewFactory.create(context, currentAd)
            request.onLayoutWillChange()
            view = created
            request.onLayoutChanged()
        } catch (failure: Exception) {
            emit(AdSlotEventType.RenderFailed, "admob_render_failed")
            close()
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

    /** 页面离开或 UMP 暂停时立即释放；关闭后的迟到回调只能销毁素材。 */
    fun close() {
        emit(AdSlotEventType.Released)
        if (!guard.close()) return
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
    }
}
