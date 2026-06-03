package com.cla.clip.feature.ad.csj

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.bytedance.sdk.openadsdk.AdSlot
import com.bytedance.sdk.openadsdk.TTAdConfig
import com.bytedance.sdk.openadsdk.TTAdConstant
import com.bytedance.sdk.openadsdk.TTAdNative
import com.bytedance.sdk.openadsdk.TTAdSdk
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 穿山甲 SDK 直接调用 facade。
 *
 * 第三方类型只出现在本文件，adapter 其它类依赖内部接口，降低 SDK 升级扩散面。
 */
@Singleton
internal class CsjSdkDirectClient @Inject constructor() : CsjSdkClient {
    /** 当前 SDK 是否可用；直接依赖构建下类存在即认为可用。 */
    override val isSdkAvailable: Boolean = true

    /** 当前 SDK 版本；来自官方 SDK 公开常量。 */
    override val sdkVersion: String = TTAdSdk.SDK_VERSION_NAME.orEmpty()

    /**
     * 初始化穿山甲 SDK。
     *
     * 不请求 SDK 权限弹窗，不传入关键词和用户数据，customController 关闭可选设备能力读取。
     */
    override fun initialize(
        context: Context,
        config: CsjAdConfig,
        debugMode: Boolean,
        callback: CsjInitCallback,
    ) {
        if (TTAdSdk.isInitSuccess()) {
            callback.onSuccess()
            return
        }

        /** 穿山甲初始化配置；只包含 AppId、调试日志和最小隐私控制。 */
        val adConfig = TTAdConfig.Builder()
            .appId(config.appId)
            .debug(debugMode)
            .allowShowNotify(false)
            .supportMultiProcess(false)
            .directDownloadNetworkType()
            .customController(CsjPrivacyController())
            .build()
        /** SDK init 返回值只代表同步受理状态，最终可用性以后续 start 回调为准。 */
        val initAccepted = TTAdSdk.init(context.applicationContext, adConfig)
        if (!initAccepted && !TTAdSdk.isInitSuccess()) {
            callback.onFailure(CsjAdReason.INIT_FAILED)
            return
        }
        TTAdSdk.start(object : TTAdSdk.Callback {
            /** SDK 异步初始化成功。 */
            override fun success() {
                callback.onSuccess()
            }

            /** SDK 异步初始化失败；错误详情不透传，避免输出 SDK 原始响应。 */
            override fun fail(code: Int, msg: String?) {
                callback.onFailure(CsjAdReason.INIT_FAILED)
            }
        })
    }

    /**
     * 加载详情页信息流广告。
     *
     * 只请求单个模板信息流广告，不做预加载，不传 mediaExtra、userId、keywords 或用户内容。
     */
    override fun loadDetailNativeAd(
        context: Context,
        config: CsjAdConfig,
        widthDp: Float,
        heightDp: Float,
        callback: CsjNativeAdLoadCallback,
    ): CsjNativeAdHandle? {
        /** SDK 管理器；初始化失败或插件不可用时可能返回 null。 */
        val adManager = TTAdSdk.getAdManager()
        /** 原生广告加载入口；使用 application context 避免持有 Activity。 */
        val adNative = adManager?.createAdNative(context.applicationContext) ?: return null
        /** 详情页广告位请求；只包含广告位 ID、尺寸和请求数量。 */
        val adSlot = AdSlot.Builder()
            .setCodeId(config.detailNativeAdSlotId)
            .setAdCount(1)
            .setSupportDeepLink(false)
            .setExpressViewAcceptedSize(widthDp, heightDp)
            .build()
        adNative.loadNativeExpressAd(adSlot, object : TTAdNative.NativeExpressAdListener {
            /** 广告加载失败；错误 code/msg 不透传到日志。 */
            override fun onError(code: Int, message: String?) {
                /** 平台常用无填充 code 不作为错误熔断，避免低填充渠道误触保险丝。 */
                if (code == 20001 || code == -3) {
                    callback.onNoFill(CsjAdReason.NO_FILL)
                } else {
                    callback.onFailure(CsjAdReason.LOAD_FAILED)
                }
            }

            /** 广告加载成功；只取第一个广告，禁止跨请求缓存复用。 */
            override fun onNativeExpressAdLoad(ads: MutableList<com.bytedance.sdk.openadsdk.TTNativeExpressAd>?) {
                /** 当前请求返回的首个广告；为空时按无填充处理。 */
                val firstAd = ads?.firstOrNull()
                if (firstAd == null) {
                    callback.onNoFill(CsjAdReason.NO_FILL)
                    return
                }
                callback.onLoaded(CsjNativeExpressAdHandle(firstAd))
            }
        })
        return null
    }
}

/** 穿山甲隐私控制器；默认关闭定位、设备标识、外部存储、录音、消息等可选读取能力。 */
private class CsjPrivacyController : com.bytedance.sdk.openadsdk.TTCustomController() {
    /** 不允许 SDK 读取定位。 */
    override fun isCanUseLocation(): Boolean = false

    /** 不允许 SDK 读取设备电话状态。 */
    override fun isCanUsePhoneState(): Boolean = false

    /** 不允许 SDK 读取应用安装列表。 */
    override fun alist(): Boolean = false

    /** 不允许 SDK 读取 Wi-Fi 状态。 */
    override fun isCanUseWifiState(): Boolean = false

    /** 不允许 SDK 写外部存储。 */
    override fun isCanUseWriteExternal(): Boolean = false

    /** 不允许 SDK 读取 Android ID。 */
    override fun isCanUseAndroidId(): Boolean = false

    /** 不允许 SDK 读取录音权限。 */
    override fun isCanUsePermissionRecordAudio(): Boolean = false

    /** 不允许 SDK 读取消息。 */
    override fun isCanUseMessage(): Boolean = false

    /** 不提供 OAID，后续若启用必须单独补合规审批。 */
    override fun getDevOaid(): String? = null

    /** 不提供 IMEI。 */
    override fun getDevImei(): String? = null

    /** 不提供 MAC 地址。 */
    override fun getMacAddress(): String? = null

    /** 不提供 Android ID。 */
    override fun getAndroidId(): String? = null
}

/** 穿山甲模板信息流广告句柄。 */
private class CsjNativeExpressAdHandle(
    /** SDK 返回的模板广告对象；只在本句柄内持有。 */
    private val ad: com.bytedance.sdk.openadsdk.TTNativeExpressAd,
) : CsjNativeAdHandle {
    /** 展示和点击回调；由 registerInteraction 写入，迟到或重复回调由外层 guard 处理。 */
    private var interactionCallback: CsjNativeAdInteractionCallback? = null

    /** 渲染回调；由 render 写入，避免 SDK listener 覆盖后丢失渲染结果。 */
    private var renderCallback: CsjNativeAdRenderCallback? = null

    /** 当前广告是否为下载类交互；v1 不支持下载类广告。 */
    override val isDownloadAd: Boolean
        get() = ad.interactionType == TTAdConstant.INTERACTION_TYPE_DOWNLOAD

    /** SDK 创建的模板广告 View。 */
    override val adView: View?
        get() = ad.expressAdView

    /** 模板广告点击区域由 SDK 内部控制，这里只监听展示和点击事件。 */
    override fun registerInteraction(container: ViewGroup, callback: CsjNativeAdInteractionCallback) {
        interactionCallback = callback
        installInteractionListener()
    }

    /** 安装统一模板广告 listener，避免交互 listener 和渲染 listener 互相覆盖。 */
    private fun installInteractionListener() {
        ad.setExpressInteractionListener(object : com.bytedance.sdk.openadsdk.TTNativeExpressAd.ExpressAdInteractionListener {
            /** SDK 通知广告被点击。 */
            override fun onAdClicked(view: View?, type: Int) {
                interactionCallback?.onClicked()
            }

            /** SDK 通知广告产生展示。 */
            override fun onAdShow(view: View?, type: Int) {
                interactionCallback?.onImpression()
            }

            /** SDK 通知渲染失败；错误详情不透传。 */
            override fun onRenderFail(view: View?, message: String?, code: Int) {
                renderCallback?.onRenderFailure(CsjAdReason.RENDER_FAILED)
            }

            /** SDK 通知渲染成功；尺寸仅用于容器约束，不写入日志。 */
            override fun onRenderSuccess(view: View?, width: Float, height: Float) {
                renderCallback?.onRenderSuccess(width, height)
            }
        })
    }

    /** 触发模板广告渲染，并把渲染状态转为内部低敏回调。 */
    override fun render(callback: CsjNativeAdRenderCallback) {
        renderCallback = callback
        installInteractionListener()
        ad.render()
    }

    /** 释放模板广告资源；SDK destroy 自身应容忍重复调用。 */
    override fun destroy() {
        ad.destroy()
    }
}
