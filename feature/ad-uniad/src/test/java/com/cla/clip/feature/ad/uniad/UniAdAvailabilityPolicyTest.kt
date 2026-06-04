package com.cla.clip.feature.ad.uniad

import com.cla.clip.feature.ad.api.AdConsentState
import com.cla.clip.feature.ad.api.AdRuntimePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** uni-ad 可用性策略测试，覆盖隐私、主进程、敏感详情、总开关和远程 kill switch。 */
class UniAdAvailabilityPolicyTest {
    /** 当前被测策略；策略本身无状态，可在测试间复用。 */
    private val policy = UniAdAvailabilityPolicy()

    /** 所有条件满足时返回可用。 */
    @Test
    fun grantedConsentAndValidConfigIsAvailable() {
        /** 当前不可用原因；null 表示可以继续初始化和请求。 */
        val reason = policy.unavailableReason(
            config = defaultConfig,
            consentState = AdConsentState.Granted,
            acceptedPrivacyPolicyVersion = "",
            runtimePolicy = defaultRuntimePolicy,
        )

        assertNull(reason)
    }

    /** 广告总开关关闭时隐藏广告。 */
    @Test
    fun globalSwitchDisabledIsRejected() {
        /** 当前不可用原因；全局关闭归为配置/能力不可用。 */
        val reason = policy.unavailableReason(
            config = defaultConfig,
            consentState = AdConsentState.Granted,
            acceptedPrivacyPolicyVersion = "",
            runtimePolicy = defaultRuntimePolicy.copy(adsGlobalEnabled = false),
        )

        assertEquals(UniAdReason.CONFIG_MISSING, reason)
    }

    /** 远程 kill switch 关闭时隐藏广告。 */
    @Test
    fun remoteKillSwitchIsRejected() {
        /** 当前不可用原因；远程策略只能关闭或降级广告。 */
        val reason = policy.unavailableReason(
            config = defaultConfig,
            consentState = AdConsentState.Granted,
            acceptedPrivacyPolicyVersion = "",
            runtimePolicy = defaultRuntimePolicy.copy(remoteKillSwitchEnabled = true),
        )

        assertEquals(UniAdReason.REMOTE_KILL_SWITCH, reason)
    }

    /** 非主进程不能初始化真实 SDK。 */
    @Test
    fun nonMainProcessIsRejected() {
        /** 当前不可用原因；辅助进程命中广告代码时直接隐藏。 */
        val reason = policy.unavailableReason(
            config = defaultConfig,
            consentState = AdConsentState.Granted,
            acceptedPrivacyPolicyVersion = "",
            runtimePolicy = defaultRuntimePolicy.copy(isMainProcess = false),
        )

        assertEquals(UniAdReason.NOT_MAIN_PROCESS, reason)
    }

    /** 敏感详情页不能展示真实广告。 */
    @Test
    fun sensitiveDetailIsRejected() {
        /** 当前不可用原因；本地敏感判断只输出低敏 reasonCode。 */
        val reason = policy.unavailableReason(
            config = defaultConfig,
            consentState = AdConsentState.Granted,
            acceptedPrivacyPolicyVersion = "",
            runtimePolicy = defaultRuntimePolicy.copy(isSensitiveContext = true),
        )

        assertEquals(UniAdReason.SENSITIVE_DETAIL_HIDDEN, reason)
    }

    /** 拒绝隐私同意时隐藏广告。 */
    @Test
    fun deniedConsentIsRejected() {
        /** 当前不可用原因；用户拒绝不能初始化真实 SDK。 */
        val reason = policy.unavailableReason(
            config = defaultConfig,
            consentState = AdConsentState.Denied,
            acceptedPrivacyPolicyVersion = "",
            runtimePolicy = defaultRuntimePolicy,
        )

        assertEquals(UniAdReason.CONSENT_DENIED, reason)
    }

    /** 隐私未知或 not_required 都不能用于真实 uni-ad。 */
    @Test
    fun unknownOrNotRequiredConsentIsRejected() {
        /** 当前 unknown 原因；真实 SDK 必须等明确同意。 */
        val unknownReason = policy.unavailableReason(
            config = defaultConfig,
            consentState = AdConsentState.Unknown,
            acceptedPrivacyPolicyVersion = "",
            runtimePolicy = defaultRuntimePolicy,
        )
        /** 当前 not_required 原因；只允许 debug 占位源使用。 */
        val notRequiredReason = policy.unavailableReason(
            config = defaultConfig,
            consentState = AdConsentState.NotRequired,
            acceptedPrivacyPolicyVersion = "",
            runtimePolicy = defaultRuntimePolicy,
        )

        assertEquals(UniAdReason.CONSENT_REVOKED, unknownReason)
        assertEquals(UniAdReason.CONSENT_REVOKED, notRequiredReason)
    }

    /** 隐私政策版本过期时隐藏广告。 */
    @Test
    fun outdatedPrivacyPolicyVersionIsRejected() {
        /** 当前配置；要求用户已同意包含 uni-ad 清单的新版本。 */
        val config = defaultConfig.copy(requiredPrivacyPolicyVersion = "2026-06-uniad")

        /** 当前不可用原因；旧隐私版本不能启动真实广告 SDK。 */
        val reason = policy.unavailableReason(
            config = config,
            consentState = AdConsentState.Granted,
            acceptedPrivacyPolicyVersion = "2026-05-old",
            runtimePolicy = defaultRuntimePolicy,
        )

        assertEquals(UniAdReason.PRIVACY_VERSION_OUTDATED, reason)
    }

    private companion object {
        /** 默认有效配置；各测试只调整一个条件。 */
        private val defaultConfig = UniAdConfig(
            appId = "123456",
            unionId = "654321",
            detailNativeAdpid = "1000000001",
            useTestAdpid = false,
            sdkDependencyEnabled = true,
            sdkVersion = "5.5.2.0606",
            requiredPrivacyPolicyVersion = "",
        )

        /** 默认运行时策略；广告总开关开启且处于主进程。 */
        private val defaultRuntimePolicy = AdRuntimePolicy(adsGlobalEnabled = true)
    }
}
