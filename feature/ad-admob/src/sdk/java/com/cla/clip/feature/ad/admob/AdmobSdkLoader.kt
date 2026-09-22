package com.cla.clip.feature.ad.admob

import android.os.Handler
import android.os.Looper
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.nativead.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException

/** 加载失败只保留低敏类别，禁止向宿主传播 SDK 原始错误消息。 */
internal class AdmobLoadFailure(
    /** 无填充是正常竞价结果，不触发初始化或渲染保险丝。 */
    val noFill: Boolean,
) : Exception("admob_load_failed")

/** SDK 回调统一切主线程；超时/取消之后到达的素材立即销毁。 */
internal object AdmobSdkLoader {
    /** SDK 回调线程不保证是主线程，所有所有权交接均在这里序列化。 */
    private val main = Handler(Looper.getMainLooper())

    /** 返回单个原生广告，由调用方拥有并销毁；取消不能阻止 SDK 网络回调。 */
    suspend fun load(unitId: String): NativeAd = suspendCancellableCoroutine { continuation ->
        NativeAdLoader.load(
            NativeAdRequest.Builder(unitId, listOf(NativeAd.NativeAdType.NATIVE)).build(),
            object : NativeAdLoaderCallback {
                /** 迟到素材不能交回已销毁页面，恢复调度竞争也必须销毁。 */
                override fun onNativeAdLoaded(nativeAd: NativeAd) {
                    main.post {
                        if (continuation.isActive) {
                            continuation.resume(nativeAd, onCancellation = { _, ad, _ -> main.post { ad.destroy() } })
                        } else {
                            nativeAd.destroy()
                        }
                    }
                }
                /** 区分无填充与其他失败，既不泄露服务端响应也不自行重试。 */
                override fun onAdFailedToLoad(adError: LoadAdError) {
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
