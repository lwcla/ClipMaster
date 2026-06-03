package com.cla.clip.feature.ad.csj

import com.cla.clip.feature.ad.api.AdRuntimePolicy

/**
 * 穿山甲广告配置。
 *
 * 配置值来自 Gradle properties / CI secret / BuildConfig，不在源码硬编码正式广告位 ID。
 */
internal data class CsjAdConfig(
    /** 穿山甲应用 AppId；空白表示当前包不能请求真实广告。 */
    val appId: String,
    /** 详情页信息流/原生广告位 ID；空白表示当前包隐藏详情页广告。 */
    val detailNativeAdSlotId: String,
    /** 当前构建是否声明使用测试广告位；release 误用测试位时运行时隐藏广告。 */
    val useTestAdSlot: Boolean,
    /** 当前 adapter 是否允许调用随包编译进来的 SDK；默认/海外包关闭时保持隐藏。 */
    val sdkDependencyEnabled: Boolean,
    /** 当前穿山甲 SDK 版本；只用于低敏诊断和构建报告。 */
    val sdkVersion: String,
    /** 当前广告 SDK 要求用户同意的隐私政策版本；空白表示暂不做版本绑定。 */
    val requiredPrivacyPolicyVersion: String,
) {
    /** 是否具备最小请求配置；不检查隐私、进程或网络等动态条件。 */
    val hasRequiredIds: Boolean
        get() = appId.isNotBlank() && detailNativeAdSlotId.isNotBlank()

    /**
     * 判断当前配置是否允许发起真实 SDK 请求。
     *
     * debug 请求允许测试广告位；非 debug 请求若仍使用测试位则隐藏，避免正式包污染收益数据。
     */
    fun isUsableFor(runtimePolicy: AdRuntimePolicy): Boolean {
        return sdkDependencyEnabled && hasRequiredIds && (runtimePolicy.debugMode || !useTestAdSlot)
    }
}
