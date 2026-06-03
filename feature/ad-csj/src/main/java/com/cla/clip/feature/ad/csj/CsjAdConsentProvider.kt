package com.cla.clip.feature.ad.csj

import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.feature.ad.api.AdConsentState
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 穿山甲广告隐私同意读取器。
 *
 * 当前项目尚未接入正式隐私弹窗，因此默认值为 unknown；真实 SDK 在 unknown/denied 下保持不可用。
 */
@Singleton
internal class CsjAdConsentProvider @Inject constructor() {
    /** 广告隐私同意状态流；调用方只读取稳定 code，不读取隐私正文。 */
    val consentStateFlow: StateFlow<String>
        get() = AppSetting.adConsentStateFlow

    /** 用户同意的广告隐私版本流；用于判断当前 SDK 清单是否已被同意。 */
    val privacyPolicyVersionFlow: StateFlow<String>
        get() = AppSetting.adPrivacyPolicyVersionFlow

    /**
     * 将 AppSetting 的稳定字符串映射为广告 API 同意状态。
     *
     * `not_required` 只允许调试/内部占位使用，真实穿山甲源会继续通过策略类拒绝。
     */
    fun toAdConsentState(value: String): AdConsentState {
        return when (value.trim().lowercase()) {
            "granted" -> AdConsentState.Granted
            "denied" -> AdConsentState.Denied
            "not_required" -> AdConsentState.NotRequired
            else -> AdConsentState.Unknown
        }
    }
}
