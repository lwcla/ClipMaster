package com.cla.clip.feature.ad.csj

import com.cla.clip.feature.ad.api.AdRuntimePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 穿山甲广告配置测试，覆盖缺失 ID、测试位和正式包保护。 */
class CsjAdConfigTest {
    /** debug 请求允许使用测试广告位，便于内部验证不污染正式收益。 */
    @Test
    fun debugRequestAllowsTestAdSlotWhenIdsExist() {
        /** 当前测试配置；AppId 和广告位 ID 均存在且声明使用测试位。 */
        val config = createConfig(useTestAdSlot = true)
        /** 当前运行时策略；debugMode 表示内部测试请求。 */
        val runtimePolicy = AdRuntimePolicy(adsGlobalEnabled = true, debugMode = true)

        assertTrue(config.isUsableFor(runtimePolicy))
    }

    /** release 请求误用测试广告位时必须隐藏广告。 */
    @Test
    fun releaseRequestRejectsTestAdSlot() {
        /** 当前测试配置；正式请求仍声明测试广告位。 */
        val config = createConfig(useTestAdSlot = true)
        /** 当前运行时策略；debugMode=false 模拟正式请求。 */
        val runtimePolicy = AdRuntimePolicy(adsGlobalEnabled = true, debugMode = false)

        assertFalse(config.isUsableFor(runtimePolicy))
    }

    /** 缺少 AppId 时不可发起广告请求。 */
    @Test
    fun missingAppIdIsNotUsable() {
        /** 当前测试配置；AppId 为空但广告位 ID 存在。 */
        val config = createConfig(appId = "")
        /** 当前运行时策略；debug 请求也不能绕过 ID 缺失。 */
        val runtimePolicy = AdRuntimePolicy(adsGlobalEnabled = true, debugMode = true)

        assertFalse(config.isUsableFor(runtimePolicy))
    }

    /** 缺少详情页广告位 ID 时不可发起广告请求。 */
    @Test
    fun missingSlotIdIsNotUsable() {
        /** 当前测试配置；广告位 ID 为空。 */
        val config = createConfig(detailNativeAdSlotId = "")
        /** 当前运行时策略；debug 请求也不能绕过广告位缺失。 */
        val runtimePolicy = AdRuntimePolicy(adsGlobalEnabled = true, debugMode = true)

        assertFalse(config.isUsableFor(runtimePolicy))
    }

    /** SDK 依赖未随包启用时不可发起广告请求。 */
    @Test
    fun disabledSdkDependencyIsNotUsable() {
        /** 当前测试配置；模拟默认/海外包没有启用穿山甲能力。 */
        val config = createConfig(sdkDependencyEnabled = false)
        /** 当前运行时策略；即使是 debug 请求也不应调用 SDK。 */
        val runtimePolicy = AdRuntimePolicy(adsGlobalEnabled = true, debugMode = true)

        assertFalse(config.isUsableFor(runtimePolicy))
    }

    /** 创建测试用穿山甲配置。 */
    private fun createConfig(
        /** 测试 AppId。 */
        appId: String = "test-app-id",
        /** 测试详情页广告位 ID。 */
        detailNativeAdSlotId: String = "test-slot-id",
        /** 是否声明当前构建使用测试广告位。 */
        useTestAdSlot: Boolean = false,
        /** 是否启用 SDK 依赖。 */
        sdkDependencyEnabled: Boolean = true,
    ): CsjAdConfig {
        return CsjAdConfig(
            appId = appId,
            detailNativeAdSlotId = detailNativeAdSlotId,
            useTestAdSlot = useTestAdSlot,
            sdkDependencyEnabled = sdkDependencyEnabled,
            sdkVersion = "test-sdk",
            requiredPrivacyPolicyVersion = "",
        )
    }
}
