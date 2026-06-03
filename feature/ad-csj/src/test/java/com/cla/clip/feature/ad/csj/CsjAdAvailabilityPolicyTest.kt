package com.cla.clip.feature.ad.csj

import com.cla.clip.feature.ad.api.AdConsentState
import com.cla.clip.feature.ad.api.AdRuntimePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 穿山甲广告可用性策略测试，覆盖隐私、进程、敏感内容和配置边界。 */
class CsjAdAvailabilityPolicyTest {
    /** 被测策略；纯逻辑对象，不依赖 Android runtime。 */
    private val policy = CsjAdAvailabilityPolicy()

    /** 默认可用配置；各测试只覆盖单一失败条件。 */
    private val defaultConfig = CsjAdConfig(
        appId = "test-app-id",
        detailNativeAdSlotId = "test-slot-id",
        useTestAdSlot = false,
        sdkDependencyEnabled = true,
        sdkVersion = "test-sdk",
        requiredPrivacyPolicyVersion = "",
    )

    /** 默认运行时策略；广告开启、主进程、非敏感详情。 */
    private val defaultRuntimePolicy = AdRuntimePolicy(adsGlobalEnabled = true)

    /** 用户同意且配置完整时应允许继续初始化。 */
    @Test
    fun grantedConsentAndValidConfigIsAvailable() {
        /** 当前不可用原因；null 表示可以进入 SDK 初始化。 */
        val reason = policy.unavailableReason(
            config = defaultConfig,
            consentState = AdConsentState.Granted,
            acceptedPrivacyPolicyVersion = "",
            runtimePolicy = defaultRuntimePolicy,
        )

        assertNull(reason)
    }

    /** 穿山甲不接受 NotRequired，同意状态必须是明确 Granted。 */
    @Test
    fun notRequiredConsentIsRejectedForRealSdk() {
        /** 当前不可用原因；真实 SDK 不能使用调试源的 NotRequired。 */
        val reason = policy.unavailableReason(
            config = defaultConfig,
            consentState = AdConsentState.NotRequired,
            acceptedPrivacyPolicyVersion = "",
            runtimePolicy = defaultRuntimePolicy,
        )

        assertEquals(CsjAdReason.CONSENT_REVOKED, reason)
    }

    /** 用户拒绝隐私同意时应隐藏广告。 */
    @Test
    fun deniedConsentIsRejected() {
        /** 当前不可用原因；拒绝同意不能初始化 SDK。 */
        val reason = policy.unavailableReason(
            config = defaultConfig,
            consentState = AdConsentState.Denied,
            acceptedPrivacyPolicyVersion = "",
            runtimePolicy = defaultRuntimePolicy,
        )

        assertEquals(CsjAdReason.CONSENT_DENIED, reason)
    }

    /** 未知隐私状态按撤回/未授权处理。 */
    @Test
    fun unknownConsentIsRejected() {
        /** 当前不可用原因；未知状态不得请求真实广告。 */
        val reason = policy.unavailableReason(
            config = defaultConfig,
            consentState = AdConsentState.Unknown,
            acceptedPrivacyPolicyVersion = "",
            runtimePolicy = defaultRuntimePolicy,
        )

        assertEquals(CsjAdReason.CONSENT_REVOKED, reason)
    }

    /** 非主进程时应优先拒绝 SDK 初始化。 */
    @Test
    fun nonMainProcessIsRejected() {
        /** 当前运行时策略；模拟 Shizuku 或辅助进程。 */
        val runtimePolicy = defaultRuntimePolicy.copy(isMainProcess = false)

        /** 当前不可用原因；主进程保护优先于隐私细节。 */
        val reason = policy.unavailableReason(
            config = defaultConfig,
            consentState = AdConsentState.Granted,
            acceptedPrivacyPolicyVersion = "",
            runtimePolicy = runtimePolicy,
        )

        assertEquals(CsjAdReason.NOT_MAIN_PROCESS, reason)
    }

    /** 敏感详情页应隐藏广告且只返回低敏 reasonCode。 */
    @Test
    fun sensitiveContextIsRejected() {
        /** 当前运行时策略；只携带敏感布尔值，不携带正文。 */
        val runtimePolicy = defaultRuntimePolicy.copy(isSensitiveContext = true)

        /** 当前不可用原因；命中敏感保护后不继续判断广告位。 */
        val reason = policy.unavailableReason(
            config = defaultConfig,
            consentState = AdConsentState.Granted,
            acceptedPrivacyPolicyVersion = "",
            runtimePolicy = runtimePolicy,
        )

        assertEquals(CsjAdReason.SENSITIVE_DETAIL_HIDDEN, reason)
    }

    /** 隐私政策版本过期时应阻止真实 SDK 请求。 */
    @Test
    fun outdatedPrivacyPolicyVersionIsRejected() {
        /** 当前配置；要求用户同意到包含广告 SDK 清单的版本。 */
        val config = defaultConfig.copy(requiredPrivacyPolicyVersion = "2026-06-csj")

        /** 当前不可用原因；用户同意版本不匹配。 */
        val reason = policy.unavailableReason(
            config = config,
            consentState = AdConsentState.Granted,
            acceptedPrivacyPolicyVersion = "2026-05-old",
            runtimePolicy = defaultRuntimePolicy,
        )

        assertEquals(CsjAdReason.PRIVACY_VERSION_OUTDATED, reason)
    }

    /** 配置缺失时应返回稳定配置原因码。 */
    @Test
    fun missingConfigIsRejected() {
        /** 当前配置；缺少详情页广告位 ID。 */
        val config = defaultConfig.copy(detailNativeAdSlotId = "")

        /** 当前不可用原因；配置缺失不能请求广告。 */
        val reason = policy.unavailableReason(
            config = config,
            consentState = AdConsentState.Granted,
            acceptedPrivacyPolicyVersion = "",
            runtimePolicy = defaultRuntimePolicy,
        )

        assertEquals(CsjAdReason.CONFIG_MISSING, reason)
    }
}
