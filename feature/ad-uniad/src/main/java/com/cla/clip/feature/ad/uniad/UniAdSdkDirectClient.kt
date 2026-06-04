package com.cla.clip.feature.ad.uniad

import android.app.Activity
import android.content.Context
import android.view.View
import io.dcloud.ads.core.DCloudAdManager
import io.dcloud.ads.core.entry.DCloudAdSlot
import io.dcloud.ads.core.v2.feed.DCFeedAd
import io.dcloud.ads.core.v2.feed.DCFeedAdListener
import io.dcloud.ads.core.v2.feed.DCFeedAdLoadListener
import io.dcloud.ads.core.v2.feed.DCFeedAdLoader
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * uni-ad SDK 直接调用 facade。
 *
 * 第三方类型只出现在本文件，adapter 其它类依赖内部接口，降低 SDK 升级扩散面。
 */
@Singleton
internal class UniAdSdkDirectClient @Inject constructor() : UniAdSdkClient {
    /** 当前 SDK 是否可用；直接依赖构建下类存在即认为可用。 */
    override val isSdkAvailable: Boolean = true

    /** 当前 SDK 版本；来自官方 SDK 公开入口，失败时回退 BuildConfig 版本。 */
    override val sdkVersion: String
        get() = runCatching { DCloudAdManager.getVersion().orEmpty() }
            .getOrDefault(BuildConfig.UNIAD_SDK_VERSION)

    /**
     * 初始化 uni-ad SDK。
     *
     * 初始化只写入 AppId、联盟 ID、debug 标记和隐私控制；不传剪贴内容、关键词或广告位 ID。
     */
    override fun initialize(
        context: Context,
        config: UniAdConfig,
        debugMode: Boolean,
        callback: UniAdInitCallback,
    ) {
        if (DCloudAdManager.isInit()) {
            applyPrivacyConfig(context.applicationContext)
            callback.onSuccess()
            return
        }

        /** uni-ad 初始化配置；只包含后台应用 ID、联盟 ID 和调试标记。 */
        val initConfig = DCloudAdManager.InitConfig()
            .setAppId(config.appId)
            .setAdId(config.unionId)
        initConfig.setDebug(debugMode)

        runCatching {
            DCloudAdManager.init(context.applicationContext, initConfig)
            applyPrivacyConfig(context.applicationContext)
        }.onSuccess {
            callback.onSuccess()
        }.onFailure {
            callback.onFailure(UniAdReason.INIT_FAILED)
        }
    }

    /**
     * 加载详情页信息流广告。
     *
     * 只请求单条信息流广告，不做预加载，不传 userId、extra、query 或用户内容。
     */
    override fun loadDetailFeedAd(
        activity: Activity,
        requestSpec: UniAdFeedRequestSpec,
        callback: UniAdFeedAdLoadCallback,
    ): UniAdFeedLoaderHandle? {
        /** 信息流 loader 需要 Activity；外层已保证 context 能找到 Activity。 */
        val loader = DCFeedAdLoader(activity)
        /** 详情页广告位请求；显式 count(1)，禁止一次请求拿多条广告。 */
        val slot = DCloudAdSlot.Builder()
            .adpid(requestSpec.adpid)
            .count(requestSpec.count)
            .build()
        loader.load(slot, object : DCFeedAdLoadListener {
            /** 广告加载成功；只取第一个广告，禁止跨请求缓存复用。 */
            override fun onFeedAdLoad(list: MutableList<DCFeedAd>?) {
                /** 当前请求返回的首个广告；为空时按无填充处理。 */
                val firstAd = list?.firstOrNull()
                if (firstAd == null) {
                    callback.onNoFill(UniAdReason.NO_FILL)
                    return
                }
                callback.onLoaded(UniAdFeedAdDirectHandle(firstAd))
            }

            /** 广告加载失败；code/message/detail 不透传，避免记录 SDK 原始错误对象和响应。 */
            override fun onError(code: Int, message: String?, detail: JSONArray?) {
                callback.onFailure(UniAdReason.LOAD_FAILED)
            }
        })
        return UniAdFeedLoaderDirectHandle()
    }

    /** 应用 uni-ad 隐私配置，默认关闭可选采集并关闭个性化广告。 */
    private fun applyPrivacyConfig(context: Context) {
        DCloudAdManager.setPrivacyConfig(UniAdPrivacyConfig())
        DCloudAdManager.setPersonalAd(context.applicationContext, false)
    }
}

/** uni-ad 隐私控制器；默认关闭 PhoneState、Storage、Location、安装列表、标识和传感器等可选读取能力。 */
private class UniAdPrivacyConfig : DCloudAdManager.PrivacyConfig() {
    /** 当前应用不针对儿童或特殊广告能力声明成年人画像。 */
    override fun isAdult(): Boolean = false

    /** 不允许 SDK 读取设备电话状态。 */
    override fun isCanUsePhoneState(): Boolean = false

    /** 不允许 SDK 读取外部存储。 */
    override fun isCanUseStorage(): Boolean = false

    /** 不允许 SDK 读取定位。 */
    override fun isCanUseLocation(): Boolean = false

    /** 不允许 SDK 读取 Wi-Fi 状态。 */
    override fun isCanUseWifiState(): Boolean = false

    /** 不允许 SDK 读取应用安装列表。 */
    override fun isCanGetInstallAppList(): Boolean = false

    /** 不允许 SDK 读取运行中应用。 */
    override fun isCanGetRunningApps(): Boolean = false

    /** 不允许 SDK 读取 MAC 地址。 */
    override fun isCanGetMacAddress(): Boolean = false

    /** 不允许 SDK 读取 Android ID。 */
    override fun isCanGetAndroidId(): Boolean = false

    /** 不允许 SDK 读取 OAID；后续启用必须单独补合规审批。 */
    override fun isCanGetOAID(): Boolean = false

    /** 不允许 SDK 读取 IP 作为可选采集项；网络请求必需信息由 SDK 自身按合规处理。 */
    override fun isCanGetIP(): Boolean = false

    /** 不启用广点通兼容同意策略；v1 未接入该渠道。 */
    override fun isGDTAgreeStrategy(): Boolean = false

    /** 不允许 SDK 读取传感器。 */
    override fun isCanUseSensor(): Boolean = false

    /** 不允许 SDK 读取运营商信息。 */
    override fun isCanUseSimOperator(): Boolean = false

    /** 不允许 SDK 使用录音权限。 */
    override fun isCanUseRecordPermission(): Boolean = false
}

/** uni-ad 信息流 loader 句柄；当前 SDK 无公开 cancel API，因此只保留幂等释放入口。 */
private class UniAdFeedLoaderDirectHandle : UniAdFeedLoaderHandle {
    /** loader 释放预留口；当前实现不持有可取消对象。 */
    override fun release() = Unit
}

/** uni-ad 信息流广告句柄。 */
private class UniAdFeedAdDirectHandle(
    /** SDK 返回的信息流广告对象；只在本句柄内持有。 */
    private val ad: DCFeedAd,
) : UniAdFeedAdHandle {
    /** 当前广告是否已经销毁；用于让 destroy 幂等。 */
    private var destroyed = false

    /**
     * 触发信息流广告渲染。
     *
     * listener 只映射低敏事件；错误 message 和关闭原因不输出原文。
     */
    override fun render(activity: Activity, callback: UniAdFeedAdRenderCallback) {
        ad.setFeedAdListener(object : DCFeedAdListener {
            /** SDK 通知渲染成功；外层随后调用 getFeedAdView(Activity)。 */
            override fun onRenderSuccess() {
                callback.onRenderSuccess()
            }

            /** SDK 通知渲染失败；错误详情不透传。 */
            override fun onRenderFail() {
                callback.onRenderFailure(UniAdReason.RENDER_FAILED)
            }

            /** SDK 通知广告被点击。 */
            override fun onClick() {
                callback.onClicked()
            }

            /** SDK 通知广告产生展示。 */
            override fun onShow() {
                callback.onImpression()
            }

            /** SDK 通知广告被关闭；关闭原因不透传，避免输出渠道原始文本。 */
            override fun onClosed(reason: String?) {
                callback.onClosed(UniAdReason.CLOSED_BY_USER)
            }

            /** SDK 通知展示错误；错误详情不透传。 */
            override fun onShowError() {
                callback.onShowError(UniAdReason.RENDER_FAILED)
            }
        })
        ad.render()
    }

    /** 获取 SDK 创建的广告 View；调用方负责确认 Activity 存在并先完成 render。 */
    override fun getAdView(activity: Activity): View? = ad.getFeedAdView(activity)

    /** 释放信息流广告资源；SDK destroy 自身应容忍重复调用，这里仍做本地幂等保护。 */
    override fun destroy() {
        if (destroyed) {
            return
        }
        destroyed = true
        ad.destroy()
    }
}
