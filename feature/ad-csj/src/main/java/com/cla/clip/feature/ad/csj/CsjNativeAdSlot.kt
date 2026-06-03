package com.cla.clip.feature.ad.csj

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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

/** 穿山甲详情页原生广告 Compose 容器。 */
@Composable
internal fun CsjNativeAdSlot(
    request: AdSlotRequest,
    config: CsjAdConfig,
    consentState: AdConsentState,
    acceptedPrivacyPolicyVersion: String,
    initializer: CsjAdInitializer,
    sdkClient: CsjSdkClient,
    availabilityPolicy: CsjAdAvailabilityPolicy,
    onEvent: (AdSlotEvent) -> Unit,
    modifier: Modifier,
) {
    /** Android Context；只传 applicationContext 给 SDK 初始化和加载，避免持有 Activity。 */
    val context = LocalContext.current
    /** 当前屏幕宽度对应的 dp 尺寸，用于模板广告请求。 */
    val widthDp = with(LocalDensity.current) { context.resources.displayMetrics.widthPixels.toDp().value }
    /** 当前请求的事件去重器；clipId/requestNonce 变化时重新创建。 */
    val eventDeduplicator = remember(request.requestNonce) { CsjAdEventDeduplicator() }
    /** 当前请求的释放保护器；多路径释放只执行一次。 */
    val releaseGuard = remember(request.requestNonce) { CsjAdReleaseGuard() }
    /** SDK 回调统一切回主线程后再更新 Compose state 或向宿主发送事件。 */
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    /** 当前已加载的广告句柄；为空时不占位。 */
    var adHandle by remember(request.requestNonce) { mutableStateOf<CsjNativeAdHandle?>(null) }
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
            adHandle?.let { handle ->
                releaseLoadedHandle(request, handle, startedAtMillis, config, releaseGuard, eventDeduplicator, onEvent)
                adHandle = null
            }
            emitCsjEvent(
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
        if (!isNetworkAvailable(context)) {
            emitCsjEvent(
                request = request,
                eventType = AdSlotEventType.LoadFailed,
                reasonCode = CsjAdReason.NO_NETWORK,
                startedAtMillis = startedAtMillis,
                sdkVersion = config.sdkVersion,
                eventDeduplicator = eventDeduplicator,
                onEvent = onEvent,
            )
            return@LaunchedEffect
        }
        emitCsjEvent(
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
            callback = object : CsjInitCallback {
                /** SDK 初始化成功后开始加载当前详情页广告。 */
                override fun onSuccess() {
                    runOnMainThread(mainHandler) {
                        if (releaseGuard.isReleased()) {
                            emitLateCallbackIgnored(request, startedAtMillis, config, eventDeduplicator, onEvent)
                            return@runOnMainThread
                        }
                        loadNativeAd(
                            context = context,
                            request = request,
                            config = config,
                            sdkClient = sdkClient,
                            widthDp = widthDp,
                            startedAtMillis = startedAtMillis,
                            releaseGuard = releaseGuard,
                            eventDeduplicator = eventDeduplicator,
                            mainHandler = mainHandler,
                            onLoaded = { loadedHandle ->
                                adHandle = loadedHandle
                                if (loadedHandle.isDownloadAd) {
                                    releaseLoadedHandle(
                                        request = request,
                                        handle = loadedHandle,
                                        startedAtMillis = startedAtMillis,
                                        config = config,
                                        releaseGuard = releaseGuard,
                                        eventDeduplicator = eventDeduplicator,
                                        onEvent = onEvent,
                                    )
                                    emitCsjEvent(
                                        request = request,
                                        eventType = AdSlotEventType.LoadFailed,
                                        reasonCode = CsjAdReason.DOWNLOAD_AD_UNSUPPORTED,
                                        startedAtMillis = startedAtMillis,
                                        sdkVersion = config.sdkVersion,
                                        eventDeduplicator = eventDeduplicator,
                                        onEvent = onEvent,
                                    )
                                    return@loadNativeAd
                                }
                                emitCsjEvent(
                                    request = request,
                                    eventType = AdSlotEventType.Loaded,
                                    reasonCode = "",
                                    startedAtMillis = startedAtMillis,
                                    sdkVersion = config.sdkVersion,
                                    eventDeduplicator = eventDeduplicator,
                                    onEvent = onEvent,
                                )
                                loadedHandle.render(object : CsjNativeAdRenderCallback {
                                    /** 渲染成功后才展示广告容器，避免加载中占空白。 */
                                    override fun onRenderSuccess(widthDp: Float, heightDp: Float) {
                                        runOnMainThread(mainHandler) {
                                            if (releaseGuard.isReleased()) {
                                                emitLateCallbackIgnored(request, startedAtMillis, config, eventDeduplicator, onEvent)
                                                return@runOnMainThread
                                            }
                                            renderReady = true
                                        }
                                    }

                                    /** 渲染失败触发保险丝并释放资源。 */
                                    override fun onRenderFailure(reasonCode: String) {
                                        runOnMainThread(mainHandler) {
                                            if (releaseGuard.isReleased()) {
                                                emitLateCallbackIgnored(request, startedAtMillis, config, eventDeduplicator, onEvent)
                                                return@runOnMainThread
                                            }
                                            emitCsjEvent(
                                                request = request,
                                                eventType = AdSlotEventType.RenderFailed,
                                                reasonCode = reasonCode,
                                                startedAtMillis = startedAtMillis,
                                                sdkVersion = config.sdkVersion,
                                                eventDeduplicator = eventDeduplicator,
                                                onEvent = onEvent,
                                            )
                                            releaseLoadedHandle(request, loadedHandle, startedAtMillis, config, releaseGuard, eventDeduplicator, onEvent)
                                        }
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
                        emitCsjEvent(
                            request = request,
                            eventType = AdSlotEventType.InitializationFailed,
                            reasonCode = reasonCode,
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
            delay(CSJ_REQUEST_TIMEOUT_MS)
            if (!renderReady && !releaseGuard.isReleased()) {
                emitCsjEvent(
                    request = request,
                    eventType = AdSlotEventType.LoadFailed,
                    reasonCode = CsjAdReason.REQUEST_TIMEOUT,
                    startedAtMillis = startedAtMillis,
                    sdkVersion = config.sdkVersion,
                    eventDeduplicator = eventDeduplicator,
                    onEvent = onEvent,
                )
                adHandle?.let { handle ->
                    releaseLoadedHandle(request, handle, startedAtMillis, config, releaseGuard, eventDeduplicator, onEvent)
                }
            }
        }
    }

    DisposableEffect(request.requestNonce) {
        onDispose {
            adHandle?.let { handle ->
                releaseLoadedHandle(request, handle, startedAtMillis, config, releaseGuard, eventDeduplicator, onEvent)
            }
        }
    }

    if (renderReady) {
        /** 广告标识文案；用于视觉展示和 TalkBack 说明。 */
        val adLabel = stringResource(id = R.string.ad_csj_detail_ad_label)
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
                modifier = Modifier.heightIn(max = CSJ_DETAIL_NATIVE_MAX_HEIGHT_DP.toDp()),
                factory = { viewContext ->
                    FrameLayout(viewContext).also { container ->
                        /** 当前 SDK 广告 View；为空时保持空容器并等待释放路径处理。 */
                        val adView = adHandle?.adView
                        if (adView != null) {
                            (adView.parent as? ViewGroup)?.removeView(adView)
                            container.addView(
                                adView,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                ),
                            )
                            adHandle?.registerInteraction(container, object : CsjNativeAdInteractionCallback {
                                /** 广告展示事件；按 requestNonce 去重。 */
                                override fun onImpression() {
                                    runOnMainThread(mainHandler) {
                                        emitCsjEvent(
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
                                        emitCsjEvent(
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
                            })
                        }
                    }
                },
                update = { container ->
                    /** Compose 重组时不重新注册点击层，只确保 SDK View 仍挂在当前容器。 */
                    val adView = adHandle?.adView
                    if (adView != null && adView.parent !== container) {
                        (adView.parent as? ViewGroup)?.removeView(adView)
                        container.addView(adView)
                    }
                },
            )
        }
    }
}

/** 加载穿山甲详情页广告，并把所有异常转成低敏事件。 */
private fun loadNativeAd(
    context: Context,
    request: AdSlotRequest,
    config: CsjAdConfig,
    sdkClient: CsjSdkClient,
    widthDp: Float,
    startedAtMillis: Long,
    releaseGuard: CsjAdReleaseGuard,
    eventDeduplicator: CsjAdEventDeduplicator,
    mainHandler: Handler,
    onLoaded: (CsjNativeAdHandle) -> Unit,
    onEvent: (AdSlotEvent) -> Unit,
) {
    runCatching {
        sdkClient.loadDetailNativeAd(
            context = context.applicationContext,
            config = config,
            widthDp = widthDp,
            heightDp = CSJ_DETAIL_NATIVE_MAX_HEIGHT_DP.toFloat(),
            callback = object : CsjNativeAdLoadCallback {
                /** SDK 成功返回广告。 */
                override fun onLoaded(ad: CsjNativeAdHandle) {
                    runOnMainThread(mainHandler) {
                        if (releaseGuard.isReleased()) {
                            emitLateCallbackIgnored(request, startedAtMillis, config, eventDeduplicator, onEvent)
                            ad.destroy()
                            return@runOnMainThread
                        }
                        onLoaded(ad)
                    }
                }

                /** SDK 返回无填充。 */
                override fun onNoFill(reasonCode: String) {
                    runOnMainThread(mainHandler) {
                        emitCsjEvent(request, AdSlotEventType.NoFill, reasonCode, startedAtMillis, config.sdkVersion, eventDeduplicator, onEvent)
                    }
                }

                /** SDK 加载失败。 */
                override fun onFailure(reasonCode: String) {
                    runOnMainThread(mainHandler) {
                        emitCsjEvent(request, AdSlotEventType.LoadFailed, reasonCode, startedAtMillis, config.sdkVersion, eventDeduplicator, onEvent)
                    }
                }
            },
        )
    }.getOrElse {
        runOnMainThread(mainHandler) {
            emitCsjEvent(
                request = request,
                eventType = AdSlotEventType.LoadFailed,
                reasonCode = CsjAdReason.ADAPTER_EXCEPTION,
                startedAtMillis = startedAtMillis,
                sdkVersion = config.sdkVersion,
                eventDeduplicator = eventDeduplicator,
                onEvent = onEvent,
            )
        }
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

/** 释放已加载广告，并按幂等规则上报 Released。 */
private fun releaseLoadedHandle(
    request: AdSlotRequest,
    handle: CsjNativeAdHandle,
    startedAtMillis: Long,
    config: CsjAdConfig,
    releaseGuard: CsjAdReleaseGuard,
    eventDeduplicator: CsjAdEventDeduplicator,
    onEvent: (AdSlotEvent) -> Unit,
) {
    releaseGuard.releaseOnce {
        runCatching { handle.destroy() }
        emitCsjEvent(
            request = request,
            eventType = AdSlotEventType.Released,
            reasonCode = CsjAdReason.RELEASED,
            startedAtMillis = startedAtMillis,
            sdkVersion = config.sdkVersion,
            eventDeduplicator = eventDeduplicator,
            onEvent = onEvent,
        )
    }
}

/** 发送 SDK 迟到回调被丢弃事件。 */
private fun emitLateCallbackIgnored(
    request: AdSlotRequest,
    startedAtMillis: Long,
    config: CsjAdConfig,
    eventDeduplicator: CsjAdEventDeduplicator,
    onEvent: (AdSlotEvent) -> Unit,
) {
    emitCsjEvent(
        request = request,
        eventType = AdSlotEventType.LoadFailed,
        reasonCode = CsjAdReason.LATE_CALLBACK_IGNORED,
        startedAtMillis = startedAtMillis,
        sdkVersion = config.sdkVersion,
        eventDeduplicator = eventDeduplicator,
        onEvent = onEvent,
    )
}

/** 统一发送穿山甲低敏广告事件。 */
private fun emitCsjEvent(
    request: AdSlotRequest,
    eventType: AdSlotEventType,
    reasonCode: String,
    startedAtMillis: Long,
    sdkVersion: String,
    eventDeduplicator: CsjAdEventDeduplicator,
    onEvent: (AdSlotEvent) -> Unit,
) {
    if (!eventDeduplicator.shouldEmit(eventType)) {
        return
    }
    onEvent(
        AdSlotEvent(
            providerId = CSJ_AD_SOURCE_ID,
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

/** 将 Int dp 转为 Compose Dp。 */
private fun Int.toDp() = androidx.compose.ui.unit.Dp(this.toFloat())
