package com.cla.clip.feature.ad.uniad

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cla.clip.feature.ad.api.AdConsentState
import com.cla.clip.feature.ad.api.AdRuntimePolicy
import com.cla.clip.feature.ad.api.AdSlotEvent
import com.cla.clip.feature.ad.api.AdSlotEventType
import com.cla.clip.feature.ad.api.AdSlotRequest
import kotlinx.coroutines.delay

/** uni-ad 详情页信息流广告 Compose 容器。 */
@Composable
internal fun UniAdFeedAdSlot(
    request: AdSlotRequest,
    config: UniAdConfig,
    consentState: AdConsentState,
    acceptedPrivacyPolicyVersion: String,
    initializer: UniAdInitializer,
    sdkClient: UniAdSdkClient,
    availabilityPolicy: UniAdAvailabilityPolicy,
    onEvent: (AdSlotEvent) -> Unit,
    modifier: Modifier,
) {
    /** Android Context；初始化只使用 applicationContext，渲染 View 时再从当前 context 查找 Activity。 */
    val context = LocalContext.current
    /** 当前 Activity；uni-ad getFeedAdView(Activity) 必须使用 Activity，找不到时隐藏广告。 */
    val activity = remember(context) { context.findActivity() }
    /** 当前请求的事件去重器；clipId/requestNonce 变化时重新创建。 */
    val eventDeduplicator = remember(request.requestNonce) { UniAdEventDeduplicator() }
    /** 当前请求的释放保护器；多路径释放只执行一次。 */
    val releaseGuard = remember(request.requestNonce) { UniAdReleaseGuard() }
    /** SDK 回调统一切回主线程后再更新 Compose state 或向宿主发送事件。 */
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    /** 当前 loader 句柄；超时或离开页面时释放引用。 */
    var loaderHandle by remember(request.requestNonce) { mutableStateOf<UniAdFeedLoaderHandle?>(null) }
    /** 当前已加载的广告句柄；为空时不占位。 */
    var adHandle by remember(request.requestNonce) { mutableStateOf<UniAdFeedAdHandle?>(null) }
    /** 当前 SDK 广告 View；渲染成功前为空，不插入容器。 */
    var adView by remember(request.requestNonce) { mutableStateOf<android.view.View?>(null) }
    /** 当前广告是否已经渲染成功；未成功前不插入容器。 */
    var renderReady by remember(request.requestNonce) { mutableStateOf(false) }
    /** 当前请求开始时间；用于计算低敏耗时。 */
    val startedAtMillis = remember(request.requestNonce) { System.currentTimeMillis() }
    /** 当前运行时策略；adapter 内部只补充真实 SDK 需要的本地判断。 */
    val runtimePolicy = remember(request.isDebugRequest, request.isSensitiveContext) {
        AdRuntimePolicy(
            adsGlobalEnabled = true,
            debugMode = request.isDebugRequest,
            isMainProcess = true,
            isSensitiveContext = request.isSensitiveContext,
        )
    }
    /** 当前可用性失败原因；非空时隐藏广告并输出低敏事件。 */
    val unavailableReason = remember(config, consentState, acceptedPrivacyPolicyVersion, runtimePolicy) {
        availabilityPolicy.unavailableReason(
            config = config,
            consentState = consentState,
            acceptedPrivacyPolicyVersion = acceptedPrivacyPolicyVersion,
            runtimePolicy = runtimePolicy,
        )
    }

    LaunchedEffect(request.requestNonce, unavailableReason) {
        if (unavailableReason != null) {
            renderReady = false
            adView = null
            releaseCurrentAd(
                request = request,
                adHandle = adHandle,
                loaderHandle = loaderHandle,
                startedAtMillis = startedAtMillis,
                sdkVersion = config.sdkVersion,
                releaseGuard = releaseGuard,
                eventDeduplicator = eventDeduplicator,
                onEvent = onEvent,
            )
            adHandle = null
            loaderHandle = null
            emitUniAdEvent(
                request = request,
                eventType = AdSlotEventType.LoadFailed,
                reasonCode = unavailableReason,
                startedAtMillis = startedAtMillis,
                sdkVersion = config.sdkVersion,
                eventDeduplicator = eventDeduplicator,
                onEvent = onEvent,
            )
        }
    }

    LaunchedEffect(request.requestNonce, unavailableReason) {
        if (unavailableReason != null) {
            return@LaunchedEffect
        }
        if (activity == null) {
            emitUniAdEvent(
                request = request,
                eventType = AdSlotEventType.LoadFailed,
                reasonCode = UniAdReason.ACTIVITY_MISSING,
                startedAtMillis = startedAtMillis,
                sdkVersion = config.sdkVersion,
                eventDeduplicator = eventDeduplicator,
                onEvent = onEvent,
            )
            return@LaunchedEffect
        }
        if (!sdkClient.isSdkAvailable) {
            emitUniAdEvent(
                request = request,
                eventType = AdSlotEventType.LoadFailed,
                reasonCode = UniAdReason.SDK_MISSING,
                startedAtMillis = startedAtMillis,
                sdkVersion = config.sdkVersion,
                eventDeduplicator = eventDeduplicator,
                onEvent = onEvent,
            )
            return@LaunchedEffect
        }
        if (!isNetworkAvailable(context)) {
            emitUniAdEvent(
                request = request,
                eventType = AdSlotEventType.LoadFailed,
                reasonCode = UniAdReason.NO_NETWORK,
                startedAtMillis = startedAtMillis,
                sdkVersion = config.sdkVersion,
                eventDeduplicator = eventDeduplicator,
                onEvent = onEvent,
            )
            return@LaunchedEffect
        }
        emitUniAdEvent(
            request = request,
            eventType = AdSlotEventType.RequestStarted,
            reasonCode = "",
            startedAtMillis = startedAtMillis,
            sdkVersion = config.sdkVersion,
            eventDeduplicator = eventDeduplicator,
            onEvent = onEvent,
        )
        initializer.ensureInitialized(
            context = context.applicationContext,
            config = config,
            runtimePolicy = runtimePolicy,
            callback = object : UniAdInitCallback {
                /** SDK 初始化成功后开始加载当前详情页广告。 */
                override fun onSuccess() {
                    runOnMainThread(mainHandler) {
                        if (releaseGuard.isReleased()) {
                            emitLateCallbackIgnored(request, startedAtMillis, config.sdkVersion, eventDeduplicator, onEvent)
                            return@runOnMainThread
                        }
                        loaderHandle = loadFeedAd(
                            activity = activity,
                            request = request,
                            config = config,
                            sdkClient = sdkClient,
                            startedAtMillis = startedAtMillis,
                            releaseGuard = releaseGuard,
                            eventDeduplicator = eventDeduplicator,
                            mainHandler = mainHandler,
                            onLoaded = { loadedHandle ->
                                adHandle = loadedHandle
                                emitUniAdEvent(
                                    request = request,
                                    eventType = AdSlotEventType.Loaded,
                                    reasonCode = "",
                                    startedAtMillis = startedAtMillis,
                                    sdkVersion = config.sdkVersion,
                                    eventDeduplicator = eventDeduplicator,
                                    onEvent = onEvent,
                                )
                                loadedHandle.render(activity, object : UniAdFeedAdRenderCallback {
                                    /** 渲染成功后获取 SDK View，成功拿到 View 才展示容器。 */
                                    override fun onRenderSuccess() {
                                        runOnMainThread(mainHandler) {
                                            if (releaseGuard.isReleased()) {
                                                emitLateCallbackIgnored(request, startedAtMillis, config.sdkVersion, eventDeduplicator, onEvent)
                                                return@runOnMainThread
                                            }
                                            /** SDK 渲染后的广告 View；为空时按渲染失败隐藏。 */
                                            val renderedView = runCatching { loadedHandle.getAdView(activity) }.getOrNull()
                                            if (renderedView == null) {
                                                emitUniAdEvent(
                                                    request = request,
                                                    eventType = AdSlotEventType.RenderFailed,
                                                    reasonCode = UniAdReason.RENDER_FAILED,
                                                    startedAtMillis = startedAtMillis,
                                                    sdkVersion = config.sdkVersion,
                                                    eventDeduplicator = eventDeduplicator,
                                                    onEvent = onEvent,
                                                )
                                                releaseCurrentAd(request, loadedHandle, loaderHandle, startedAtMillis, config.sdkVersion, releaseGuard, eventDeduplicator, onEvent)
                                                return@runOnMainThread
                                            }
                                            adView = renderedView
                                            renderReady = true
                                        }
                                    }

                                    /** 渲染失败触发保险丝并释放资源。 */
                                    override fun onRenderFailure(reasonCode: String) {
                                        runOnMainThread(mainHandler) {
                                            if (releaseGuard.isReleased()) {
                                                emitLateCallbackIgnored(request, startedAtMillis, config.sdkVersion, eventDeduplicator, onEvent)
                                                return@runOnMainThread
                                            }
                                            emitUniAdEvent(
                                                request = request,
                                                eventType = AdSlotEventType.RenderFailed,
                                                reasonCode = reasonCode.ifBlank { UniAdReason.RENDER_FAILED },
                                                startedAtMillis = startedAtMillis,
                                                sdkVersion = config.sdkVersion,
                                                eventDeduplicator = eventDeduplicator,
                                                onEvent = onEvent,
                                            )
                                            releaseCurrentAd(request, loadedHandle, loaderHandle, startedAtMillis, config.sdkVersion, releaseGuard, eventDeduplicator, onEvent)
                                        }
                                    }

                                    /** 广告展示事件；按 requestNonce 去重。 */
                                    override fun onImpression() {
                                        runOnMainThread(mainHandler) {
                                            emitUniAdEvent(
                                                request = request,
                                                eventType = AdSlotEventType.Impression,
                                                reasonCode = "",
                                                startedAtMillis = startedAtMillis,
                                                sdkVersion = config.sdkVersion,
                                                eventDeduplicator = eventDeduplicator,
                                                onEvent = onEvent,
                                            )
                                        }
                                    }

                                    /** 广告点击事件；按 requestNonce 去重。 */
                                    override fun onClicked() {
                                        runOnMainThread(mainHandler) {
                                            emitUniAdEvent(
                                                request = request,
                                                eventType = AdSlotEventType.Clicked,
                                                reasonCode = "",
                                                startedAtMillis = startedAtMillis,
                                                sdkVersion = config.sdkVersion,
                                                eventDeduplicator = eventDeduplicator,
                                                onEvent = onEvent,
                                            )
                                        }
                                    }

                                    /** 用户关闭广告后释放并隐藏当前广告位，本详情页生命周期内不重试。 */
                                    override fun onClosed(reasonCode: String) {
                                        runOnMainThread(mainHandler) {
                                            if (releaseGuard.isReleased()) {
                                                emitLateCallbackIgnored(request, startedAtMillis, config.sdkVersion, eventDeduplicator, onEvent)
                                                return@runOnMainThread
                                            }
                                            renderReady = false
                                            adView = null
                                            releaseCurrentAd(request, loadedHandle, loaderHandle, startedAtMillis, config.sdkVersion, releaseGuard, eventDeduplicator, onEvent, reasonCode)
                                        }
                                    }

                                    /** 展示错误按渲染失败处理，释放当前广告资源。 */
                                    override fun onShowError(reasonCode: String) {
                                        onRenderFailure(reasonCode)
                                    }
                                })
                            },
                            onEvent = onEvent,
                        )
                    }
                }

                /** SDK 初始化失败触发保险丝。 */
                override fun onFailure(reasonCode: String) {
                    runOnMainThread(mainHandler) {
                        emitUniAdEvent(
                            request = request,
                            eventType = AdSlotEventType.InitializationFailed,
                            reasonCode = reasonCode.ifBlank { UniAdReason.INIT_FAILED },
                            startedAtMillis = startedAtMillis,
                            sdkVersion = config.sdkVersion,
                            eventDeduplicator = eventDeduplicator,
                            onEvent = onEvent,
                        )
                    }
                }
            },
        )
    }

    LaunchedEffect(request.requestNonce, renderReady) {
        if (!renderReady) {
            delay(UNIAD_REQUEST_TIMEOUT_MS)
            if (!renderReady && !releaseGuard.isReleased()) {
                emitUniAdEvent(
                    request = request,
                    eventType = AdSlotEventType.LoadFailed,
                    reasonCode = UniAdReason.REQUEST_TIMEOUT,
                    startedAtMillis = startedAtMillis,
                    sdkVersion = config.sdkVersion,
                    eventDeduplicator = eventDeduplicator,
                    onEvent = onEvent,
                )
                releaseCurrentAd(request, adHandle, loaderHandle, startedAtMillis, config.sdkVersion, releaseGuard, eventDeduplicator, onEvent)
            }
        }
    }

    DisposableEffect(request.requestNonce) {
        onDispose {
            releaseCurrentAd(request, adHandle, loaderHandle, startedAtMillis, config.sdkVersion, releaseGuard, eventDeduplicator, onEvent)
        }
    }

    if (renderReady && adView != null) {
        /** 广告标识文案；用于视觉展示和 TalkBack 说明。 */
        val adLabel = stringResource(id = R.string.ad_uniad_detail_ad_label)
        Column(
            modifier = modifier
                .semantics { contentDescription = adLabel },
        ) {
            Row(modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)) {
                Text(
                    text = adLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AndroidView(
                modifier = Modifier.heightIn(max = UNIAD_DETAIL_NATIVE_MAX_HEIGHT_DP.dp),
                factory = { viewContext ->
                    FrameLayout(viewContext).also { container ->
                        /** 当前 SDK 广告 View；加入容器前先从旧 parent 移除，避免崩溃。 */
                        val currentAdView = adView
                        if (currentAdView != null) {
                            (currentAdView.parent as? ViewGroup)?.removeView(currentAdView)
                            container.addView(
                                currentAdView,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                ),
                            )
                        }
                    }
                },
                update = { container ->
                    /** Compose 重组时不重新包裹 clickable，只确保 SDK View 仍挂在当前容器。 */
                    val currentAdView = adView
                    if (currentAdView != null && currentAdView.parent !== container) {
                        (currentAdView.parent as? ViewGroup)?.removeView(currentAdView)
                        container.addView(
                            currentAdView,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ),
                        )
                    }
                },
            )
        }
    }
}

/** 加载 uni-ad 详情页信息流广告，并把所有异常转成低敏事件。 */
private fun loadFeedAd(
    activity: Activity,
    request: AdSlotRequest,
    config: UniAdConfig,
    sdkClient: UniAdSdkClient,
    startedAtMillis: Long,
    releaseGuard: UniAdReleaseGuard,
    eventDeduplicator: UniAdEventDeduplicator,
    mainHandler: Handler,
    onLoaded: (UniAdFeedAdHandle) -> Unit,
    onEvent: (AdSlotEvent) -> Unit,
): UniAdFeedLoaderHandle? {
    return runCatching {
        sdkClient.loadDetailFeedAd(
            activity = activity,
            requestSpec = UniAdFeedRequestSpec(adpid = config.detailNativeAdpid),
            callback = object : UniAdFeedAdLoadCallback {
                /** SDK 成功返回广告。 */
                override fun onLoaded(ad: UniAdFeedAdHandle) {
                    runOnMainThread(mainHandler) {
                        if (releaseGuard.isReleased()) {
                            emitLateCallbackIgnored(request, startedAtMillis, config.sdkVersion, eventDeduplicator, onEvent)
                            ad.destroy()
                            return@runOnMainThread
                        }
                        onLoaded(ad)
                    }
                }

                /** SDK 返回无填充。 */
                override fun onNoFill(reasonCode: String) {
                    runOnMainThread(mainHandler) {
                        emitUniAdEvent(request, AdSlotEventType.NoFill, reasonCode, startedAtMillis, config.sdkVersion, eventDeduplicator, onEvent)
                    }
                }

                /** SDK 加载失败。 */
                override fun onFailure(reasonCode: String) {
                    runOnMainThread(mainHandler) {
                        emitUniAdEvent(request, AdSlotEventType.LoadFailed, reasonCode, startedAtMillis, config.sdkVersion, eventDeduplicator, onEvent)
                    }
                }
            },
        )
    }.getOrElse {
        runOnMainThread(mainHandler) {
            emitUniAdEvent(
                request = request,
                eventType = AdSlotEventType.LoadFailed,
                reasonCode = UniAdReason.ADAPTER_EXCEPTION,
                startedAtMillis = startedAtMillis,
                sdkVersion = config.sdkVersion,
                eventDeduplicator = eventDeduplicator,
                onEvent = onEvent,
            )
        }
        null
    }
}

/** 在主线程执行 SDK 回调产生的状态更新和事件上报。 */
private fun runOnMainThread(
    /** 主线程 Handler。 */
    mainHandler: Handler,
    /** 需要在主线程执行的动作。 */
    block: () -> Unit,
) {
    if (Looper.myLooper() == mainHandler.looper) {
        block()
    } else {
        mainHandler.post { block() }
    }
}

/** 释放已加载广告和 loader，并按幂等规则上报 Released。 */
private fun releaseCurrentAd(
    request: AdSlotRequest,
    adHandle: UniAdFeedAdHandle?,
    loaderHandle: UniAdFeedLoaderHandle?,
    startedAtMillis: Long,
    sdkVersion: String,
    releaseGuard: UniAdReleaseGuard,
    eventDeduplicator: UniAdEventDeduplicator,
    onEvent: (AdSlotEvent) -> Unit,
    reasonCode: String = UniAdReason.RELEASED,
) {
    if (adHandle == null && loaderHandle == null) {
        return
    }
    releaseGuard.releaseOnce {
        runCatching { adHandle?.destroy() }
        runCatching { loaderHandle?.release() }
        emitUniAdEvent(
            request = request,
            eventType = AdSlotEventType.Released,
            reasonCode = reasonCode,
            startedAtMillis = startedAtMillis,
            sdkVersion = sdkVersion,
            eventDeduplicator = eventDeduplicator,
            onEvent = onEvent,
        )
    }
}

/** 发送 SDK 迟到回调被丢弃事件。 */
private fun emitLateCallbackIgnored(
    request: AdSlotRequest,
    startedAtMillis: Long,
    sdkVersion: String,
    eventDeduplicator: UniAdEventDeduplicator,
    onEvent: (AdSlotEvent) -> Unit,
) {
    emitUniAdEvent(
        request = request,
        eventType = AdSlotEventType.LoadFailed,
        reasonCode = UniAdReason.LATE_CALLBACK_IGNORED,
        startedAtMillis = startedAtMillis,
        sdkVersion = sdkVersion,
        eventDeduplicator = eventDeduplicator,
        onEvent = onEvent,
    )
}

/** 统一发送 uni-ad 低敏广告事件。 */
private fun emitUniAdEvent(
    request: AdSlotRequest,
    eventType: AdSlotEventType,
    reasonCode: String,
    startedAtMillis: Long,
    sdkVersion: String,
    eventDeduplicator: UniAdEventDeduplicator,
    onEvent: (AdSlotEvent) -> Unit,
) {
    if (!eventDeduplicator.shouldEmit(eventType)) {
        return
    }
    onEvent(
        AdSlotEvent(
            providerId = UNIAD_AD_SOURCE_ID,
            placementId = request.placement.placementId,
            eventType = eventType,
            reasonCode = reasonCode,
            durationMs = System.currentTimeMillis() - startedAtMillis,
            isDebug = request.isDebugRequest,
            sdkVersion = sdkVersion,
        ),
    )
}

/** 检查当前网络是否可用；无网时直接隐藏广告，不展示错误文案。 */
private fun isNetworkAvailable(context: Context): Boolean {
    /** ConnectivityManager 系统服务；获取失败时按无网络处理，避免请求卡顿。 */
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    /** 当前默认网络；为空表示系统没有可用网络。 */
    val network = connectivityManager.activeNetwork ?: return false
    /** 当前默认网络能力；缺少互联网能力时视为不可请求广告。 */
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** 从 Compose AndroidView context 向外查找 Activity；找不到时返回 null，不做强转。 */
internal tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
