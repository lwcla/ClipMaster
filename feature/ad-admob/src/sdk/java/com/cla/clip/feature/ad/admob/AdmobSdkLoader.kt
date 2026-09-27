package com.cla.clip.feature.ad.admob

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cla.clip.feature.ad.api.AdPlacement
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.nativead.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException
import java.util.concurrent.atomic.AtomicLong

/** 加载失败只保留低敏类别，禁止向宿主传播 SDK 原始错误消息。 */
internal class AdmobLoadFailure(
    /** 无填充是正常竞价结果，不触发初始化或渲染保险丝。 */
    val noFill: Boolean,
) : Exception("admob_load_failed")

/** SDK 回调统一切主线程；超时/取消之后到达的素材立即销毁。 */
internal object AdmobSdkLoader {
    /** SDK 回调线程不保证是主线程，所有所有权交接均在这里序列化。 */
    private val main = Handler(Looper.getMainLooper())
    /** 进程内诊断序号不关联设备、页面内容或广告平台响应，不持久化。 */
    private val requestSequence = AtomicLong()

    /** 返回单个原生广告；广告单元仅用于请求，低敏位置用于诊断；取消不能阻止 SDK 网络回调。 */
    suspend fun load(unitId: String, placement: AdPlacement): NativeAd = suspendCancellableCoroutine { continuation ->
        /** 同次 SDK 请求的开始和终态共享序号，不暴露宿主内容身份或正式广告单元。 */
        val requestId = requestSequence.incrementAndGet()
        if (BuildConfig.DEBUG) Log.d("AdmobLoader", "原生广告加载开始 requestId=$requestId placement=${placement.placementId}")
        NativeAdLoader.load(
            NativeAdRequest.Builder(unitId, listOf(NativeAd.NativeAdType.NATIVE)).build(),
            object : NativeAdLoaderCallback {
                /** 迟到素材不能交回已销毁页面，恢复调度竞争也必须销毁。 */
                override fun onNativeAdLoaded(nativeAd: NativeAd) {
                    if (BuildConfig.DEBUG) Log.d("AdmobLoader", "原生广告加载成功 requestId=$requestId placement=${placement.placementId}")
                    main.post {
                        if (continuation.isActive) {
                            continuation.resume(nativeAd, onCancellation = { _, ad, _ -> main.post { ad.destroy() } })
                        } else {
                            nativeAd.destroy()
                        }
                    }
                }
                /** 调试包只记录 SDK 错误类别及响应数量；禁止错误原文、响应标识和完整响应，不自行重试。 */
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    if (BuildConfig.DEBUG) Log.d(
                        "AdmobLoader",
                        "原生广告加载失败 requestId=$requestId placement=${placement.placementId}" +
                            " sdkErrorCode=${adError.code} sdkErrorValue=${adError.code.value}" +
                            " hasResponseInfo=${adError.responseInfo != null}" +
                            " adSourceResponseCount=${adError.responseInfo?.adSourceResponses?.size ?: 0}",
                    )
                    main.post {
                        if (continuation.isActive) {
                            continuation.resumeWithException(AdmobLoadFailure(adError.code == LoadAdError.ErrorCode.NO_FILL))
                        }
                    }
                }
            },
        )
    }
}
