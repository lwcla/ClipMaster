package com.cla.clip.feature.ad.csj

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** 穿山甲 SDK facade，隔离第三方 SDK 类型，方便单元测试和 SDK 升级。 */
internal interface CsjSdkClient {
    /** 当前 SDK 是否随包可用；不可用时 adapter 直接隐藏广告。 */
    val isSdkAvailable: Boolean

    /** SDK 版本号；只用于低敏诊断，未知时返回空字符串。 */
    val sdkVersion: String

    /**
     * 初始化穿山甲 SDK。
     *
     * 回调只返回低敏成功/失败；调用方负责保证隐私同意、主进程和总开关已经满足。
     */
    fun initialize(
        context: Context,
        config: CsjAdConfig,
        debugMode: Boolean,
        callback: CsjInitCallback,
    )

    /**
     * 加载详情页信息流广告。
     *
     * loader 必须只请求一个广告；跨 clipId 复用或预加载由调用方禁止。
     */
    fun loadDetailNativeAd(
        context: Context,
        config: CsjAdConfig,
        widthDp: Float,
        heightDp: Float,
        callback: CsjNativeAdLoadCallback,
    ): CsjNativeAdHandle?
}

/** 穿山甲 SDK 初始化回调。 */
internal interface CsjInitCallback {
    /** SDK 初始化成功或已经初始化成功。 */
    fun onSuccess()

    /** SDK 初始化失败；reasonCode 必须保持低敏。 */
    fun onFailure(reasonCode: String)
}

/** 穿山甲信息流广告加载回调。 */
internal interface CsjNativeAdLoadCallback {
    /** 广告加载成功。 */
    fun onLoaded(ad: CsjNativeAdHandle)

    /** 广告无填充。 */
    fun onNoFill(reasonCode: String)

    /** 广告加载失败。 */
    fun onFailure(reasonCode: String)
}

/** 穿山甲信息流广告句柄，adapter 通过它渲染和释放广告。 */
internal interface CsjNativeAdHandle {
    /** 当前广告是否为下载类交互；v1 不支持下载类广告。 */
    val isDownloadAd: Boolean

    /** 广告 View；由 SDK 创建，调用方只负责挂载和释放。 */
    val adView: View?

    /** 注册 SDK 交互监听，点击区域严格交给 SDK，不在外层自加 clickable。 */
    fun registerInteraction(container: ViewGroup, callback: CsjNativeAdInteractionCallback)

    /** 触发广告渲染；部分广告 View 可能已经由 SDK 完成渲染，该方法需保持幂等。 */
    fun render(callback: CsjNativeAdRenderCallback)

    /** 释放 SDK 广告资源；必须允许重复调用。 */
    fun destroy()
}

/** 穿山甲广告展示和点击回调。 */
internal interface CsjNativeAdInteractionCallback {
    /** 广告产生展示。 */
    fun onImpression()

    /** 广告被点击。 */
    fun onClicked()
}

/** 穿山甲广告渲染回调。 */
internal interface CsjNativeAdRenderCallback {
    /** 广告渲染成功，参数为 SDK 返回的宽高 dp。 */
    fun onRenderSuccess(widthDp: Float, heightDp: Float)

    /** 广告渲染失败；reasonCode 必须保持低敏。 */
    fun onRenderFailure(reasonCode: String)
}
