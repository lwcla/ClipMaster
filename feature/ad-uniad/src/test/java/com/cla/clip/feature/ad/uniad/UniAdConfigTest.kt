package com.cla.clip.feature.ad.uniad

import com.cla.clip.feature.ad.api.AdRuntimePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** uni-ad 配置规则测试，覆盖 ID 缺失、debug/release 测试位隔离和 SDK 依赖开关。 */
class UniAdConfigTest {
    /** 缺少 AppId 时配置不可用。 */
    @Test
    fun missingAppIdIsNotUsable() {
        /** 当前测试配置；只缺 AppId，其它字段保持有效以定位失败原因。 */
        val config = defaultConfig.copy(appId = "")
        /** 当前运行时策略；debug 请求允许测试 adpid，但不能绕过缺 ID。 */
        val runtimePolicy = AdRuntimePolicy(adsGlobalEnabled = true, debugMode = true)

        assertFalse(config.isUsableFor(runtimePolicy))
    }

    /** 缺少联盟 ID 时配置不可用。 */
    @Test
    fun missingUnionIdIsNotUsable() {
        /** 当前测试配置；只缺联盟 ID，其它字段保持有效以定位失败原因。 */
        val config = defaultConfig.copy(unionId = "")
        /** 当前运行时策略；debug 请求允许测试 adpid，但不能绕过缺联盟 ID。 */
        val runtimePolicy = AdRuntimePolicy(adsGlobalEnabled = true, debugMode = true)

        assertFalse(config.isUsableFor(runtimePolicy))
    }

    /** 缺少详情页 adpid 时配置不可用。 */
    @Test
    fun missingAdpidIsNotUsable() {
        /** 当前测试配置；只缺详情页 adpid，其它字段保持有效以定位失败原因。 */
        val config = defaultConfig.copy(detailNativeAdpid = "")
        /** 当前运行时策略；debug 请求允许测试 adpid，但不能绕过缺 adpid。 */
        val runtimePolicy = AdRuntimePolicy(adsGlobalEnabled = true, debugMode = true)

        assertFalse(config.isUsableFor(runtimePolicy))
    }

    /** SDK 依赖未启用时配置不可用。 */
    @Test
    fun disabledSdkDependencyIsNotUsable() {
        /** 当前测试配置；模拟默认/海外包未启用 uni-ad adapter 的运行时状态。 */
        val config = defaultConfig.copy(sdkDependencyEnabled = false)
        /** 当前运行时策略；即使是 debug 请求也不能调用未启用的真实 SDK。 */
        val runtimePolicy = AdRuntimePolicy(adsGlobalEnabled = true, debugMode = true)

        assertFalse(config.isUsableFor(runtimePolicy))
    }

    /** debug/internal 请求允许使用测试 adpid。 */
    @Test
    fun debugRequestCanUseTestAdpid() {
        /** 当前测试配置；模拟 debug/internal 后台测试广告位。 */
        val config = defaultConfig.copy(useTestAdpid = true)
        /** 当前运行时策略；debugMode 为 true 时允许测试 adpid。 */
        val runtimePolicy = AdRuntimePolicy(adsGlobalEnabled = true, debugMode = true)

        assertTrue(config.isUsableFor(runtimePolicy))
    }

    /** release 请求不允许使用测试 adpid。 */
    @Test
    fun releaseRequestCannotUseTestAdpid() {
        /** 当前测试配置；模拟正式包误带测试 adpid 标记。 */
        val config = defaultConfig.copy(useTestAdpid = true)
        /** 当前运行时策略；非 debug 请求不能使用测试 adpid。 */
        val runtimePolicy = AdRuntimePolicy(adsGlobalEnabled = true, debugMode = false)

        assertFalse(config.isUsableFor(runtimePolicy))
    }

    private companion object {
        /** 默认有效配置；各测试只覆盖自己关心的字段。 */
        private val defaultConfig = UniAdConfig(
            appId = "123456",
            unionId = "654321",
            detailNativeAdpid = "1000000001",
            useTestAdpid = false,
            sdkDependencyEnabled = true,
            sdkVersion = "5.5.2.0606",
            requiredPrivacyPolicyVersion = "",
        )
    }
}
