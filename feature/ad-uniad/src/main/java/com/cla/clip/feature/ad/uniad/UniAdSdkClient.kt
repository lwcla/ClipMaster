package com.cla.clip.feature.ad.uniad

import android.app.Activity
import android.content.Context
import android.view.View

/** uni-ad SDK facade，隔离第三方 SDK 类型，方便单元测试和 SDK 升级。 */
internal interface UniAdSdkClient {
    /** 当前 SDK 是否随包可用；不可用时 adapter 直接隐藏广告。 */
    val isSdkAvailable: Boolean

    /** SDK 版本号；只用于低敏诊断，未知时返回空字符串。 */
    val sdkVersion: String

    /**
     * 初始化 uni-ad SDK。
     *
     * 回调只返回低敏成功/失败；调用方负责保证隐私同意、主进程和总开关已经满足。
     */
    fun initialize(
        context: Context,
        config: UniAdConfig,
        debugMode: Boolean,
        callback: UniAdInitCallback,
    )

    /**
     * 加载详情页信息流广告。
     *
     * loader 必须只请求一个广告；跨 clipId 复用或预加载由调用方禁止。
     */
    fun loadDetailFeedAd(
        activity: Activity,
        requestSpec: UniAdFeedRequestSpec,
        callback: UniAdFeedAdLoadCallback,
    ): UniAdFeedLoaderHandle?
}

/** uni-ad SDK 初始化回调。 */
internal interface UniAdInitCallback {
    /** SDK 初始化成功或已经初始化成功。 */
    fun onSuccess()

    /** SDK 初始化失败；reasonCode 必须保持低敏。 */
    fun onFailure(reasonCode: String)
}

/** 详情页信息流请求规格，只包含低敏广告位参数和请求数量。 */
internal data class UniAdFeedRequestSpec(
    /** 详情页信息流 adpid；只用于构造 SDK 请求，不进入日志事件。 */
    val adpid: String,
    /** 单次请求数量；详情页固定为 1，避免一次请求拿多条广告。 */
    val count: Int = UNIAD_DETAIL_NATIVE_REQUEST_COUNT,
)

/** uni-ad 信息流广告加载回调。 */
internal interface UniAdFeedAdLoadCallback {
    /** 广告加载成功。 */
    fun onLoaded(ad: UniAdFeedAdHandle)

    /** 广告无填充。 */
    fun onNoFill(reasonCode: String)

    /** 广告加载失败。 */
    fun onFailure(reasonCode: String)
}

/** uni-ad 信息流 loader 句柄，保留取消/释放预留口，避免未来 SDK 增加取消 API 时扩散。 */
internal interface UniAdFeedLoaderHandle {
    /** 释放 loader 相关引用；当前 SDK 无公开 cancel API，调用方仍按幂等释放处理。 */
    fun release()
}

/** uni-ad 信息流广告句柄，adapter 通过它渲染、取 View 和释放广告。 */
internal interface UniAdFeedAdHandle {
    /** 触发广告渲染；渲染成功后才允许调用 getAdView。 */
    fun render(activity: Activity, callback: UniAdFeedAdRenderCallback)

    /** 获取 SDK 创建的广告 View；必须在 render 成功后调用。 */
    fun getAdView(activity: Activity): View?

    /** 释放 SDK 广告资源；必须允许重复调用。 */
    fun destroy()
}

/** uni-ad 广告渲染、展示、点击和关闭回调。 */
internal interface UniAdFeedAdRenderCallback {
    /** 广告渲染成功。 */
    fun onRenderSuccess()

    /** 广告渲染失败；reasonCode 必须保持低敏。 */
    fun onRenderFailure(reasonCode: String)

    /** 广告产生展示。 */
    fun onImpression()

    /** 广告被点击。 */
    fun onClicked()

    /** 用户关闭当前广告。 */
    fun onClosed(reasonCode: String)

    /** 广告展示错误；reasonCode 必须保持低敏。 */
    fun onShowError(reasonCode: String)
}
