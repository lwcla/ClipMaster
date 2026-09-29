package com.cla.clip.feature.ad.csj

import com.cla.clip.base.general.config.ad.AdSettingsRepository
import com.cla.clip.base.general.config.ad.AdSettingsSnapshot
import com.cla.clip.base.general.utils.ApplicationScope
import com.cla.clip.base.general.utils.logE
import com.cla.clip.feature.ad.api.AdConsentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 穿山甲广告隐私同意读取器。
 *
 * 当前项目尚未接入正式隐私弹窗，因此默认值为 unknown；真实 SDK 在 unknown/denied 下保持不可用。
 */
@Singleton
internal class CsjAdConsentProvider @Inject constructor(
    /** 只读已提交隐私状态；读取失败时保险默认值不会启动 SDK。 */
    adSettingsRepository: AdSettingsRepository,
    /** 来源级长生命周期流用于同步 SDK 的隐私门禁，不持有页面。 */
    @ApplicationScope applicationScope: CoroutineScope,
) {
    /** 同意状态与政策版本保持同一已提交快照；null 时真实 SDK 不具备授权资格。 */
    val settingsFlow: StateFlow<AdSettingsSnapshot?> = adSettingsRepository.data
        .map { settings -> settings as AdSettingsSnapshot? }
        .catch { error ->
            if (error is CancellationException) throw error
            // 已读取过授权也必须在后续存储错误时撤销来源资格。
            logE("CsjAdConsentProvider", error) { "读取广告同意状态失败 reasonCode=ad_settings_read_failed" }
            emit(null)
        }
        .stateIn(applicationScope, SharingStarted.Eagerly, null)

    /**
     * 将已提交的稳定字符串映射为广告 API 同意状态。
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
