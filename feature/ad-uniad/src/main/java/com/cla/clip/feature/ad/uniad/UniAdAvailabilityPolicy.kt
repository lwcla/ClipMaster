package com.cla.clip.feature.ad.uniad

import com.cla.clip.feature.ad.api.AdConsentState
import com.cla.clip.feature.ad.api.AdRuntimePolicy
import javax.inject.Inject

/** uni-ad 广告源可用性策略，集中处理配置、隐私、主进程、敏感上下文和测试位边界。 */
internal class UniAdAvailabilityPolicy @Inject constructor() {

    /**
     * 返回当前广告源是否可用。
     *
     * 该方法不触发 SDK 初始化，只做纯规则判断，方便选择器和单元测试复用。
     */
    fun isAvailable(
        config: UniAdConfig,
        consentState: AdConsentState,
        acceptedPrivacyPolicyVersion: String,
        runtimePolicy: AdRuntimePolicy,
    ): Boolean {
        return unavailableReason(
            config = config,
            consentState = consentState,
            acceptedPrivacyPolicyVersion = acceptedPrivacyPolicyVersion,
            runtimePolicy = runtimePolicy,
        ) == null
    }

    /**
     * 返回不可用原因。
     *
     * null 表示可以继续进入 SDK 初始化或广告请求；非空 reasonCode 只包含低敏状态。
     */
    fun unavailableReason(
        config: UniAdConfig,
        consentState: AdConsentState,
        acceptedPrivacyPolicyVersion: String,
        runtimePolicy: AdRuntimePolicy,
    ): String? {
        if (!runtimePolicy.adsGlobalEnabled) {
            return UniAdReason.CONFIG_MISSING
        }
        if (runtimePolicy.remoteKillSwitchEnabled) {
            return UniAdReason.REMOTE_KILL_SWITCH
        }
        if (!runtimePolicy.isMainProcess) {
            return UniAdReason.NOT_MAIN_PROCESS
        }
        if (runtimePolicy.isSensitiveContext) {
            return UniAdReason.SENSITIVE_DETAIL_HIDDEN
        }
        if (consentState == AdConsentState.Denied) {
            return UniAdReason.CONSENT_DENIED
        }
        if (consentState == AdConsentState.Unknown || consentState == AdConsentState.NotRequired) {
            return UniAdReason.CONSENT_REVOKED
        }
        if (config.requiredPrivacyPolicyVersion.isNotBlank() &&
            acceptedPrivacyPolicyVersion.trim() != config.requiredPrivacyPolicyVersion
        ) {
            return UniAdReason.PRIVACY_VERSION_OUTDATED
        }
        if (!config.isUsableFor(runtimePolicy)) {
            return UniAdReason.CONFIG_MISSING
        }
        return null
    }
}
