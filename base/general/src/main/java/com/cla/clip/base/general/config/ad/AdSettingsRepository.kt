package com.cla.clip.base.general.config.ad

import androidx.datastore.preferences.core.Preferences
import com.cla.clip.base.general.config.datastore.HostSettingsKeys
import com.cla.clip.base.general.config.datastore.HostSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** 一份已提交的本机广告配置；读取失败由 Flow 或挂起调用直接传播。 */
data class AdSettingsSnapshot(
    /** 当前选择的广告源，空白值映射为自动选择。 */
    val activeSourceId: String,
    /** 用户控制的广告总开关，不代表 SDK 已获得同意。 */
    val adsGlobalEnabled: Boolean,
    /** 稳定隐私同意 code，未知值始终映射为 unknown。 */
    val consentState: String,
    /** 用户同意时对应的政策版本，空值表示尚无可验证版本。 */
    val privacyPolicyVersion: String,
)

/** 广告本机设置仓库；同意状态与政策版本成组提交。 */
class AdSettingsRepository @Inject internal constructor(
    /** 宿主唯一 DataStore 介质。 */
    private val store: HostSettingsStore,
) {
    companion object {
        /** 广告源默认使用宿主自动选择。 */
        const val DEFAULT_ACTIVE_SOURCE_ID = "auto"
        /** 新安装保留总开关开启，但 SDK 仍必须经过独立隐私门禁。 */
        const val DEFAULT_ADS_GLOBAL_ENABLED = true
        /** 未同意的安全默认状态。 */
        const val DEFAULT_CONSENT_STATE = "unknown"
    }

    /** 已提交广告配置流；其他领域更新不会发出等价快照。 */
    val data: Flow<AdSettingsSnapshot> = store.data.map(::mapSnapshot).distinctUntilChanged()

    /** 读取一次真实配置；IO 或损坏错误不能被解释为同意。 */
    suspend fun snapshot(): AdSettingsSnapshot = mapSnapshot(store.snapshot())

    /** 保存广告源；空白值归一化为自动选择。 */
    suspend fun setActiveSourceId(value: String) {
        /** 本次选择的稳定来源标识。 */
        val normalized = value.trim().ifBlank { DEFAULT_ACTIVE_SOURCE_ID }
        store.edit { preferences -> preferences[HostSettingsKeys.ACTIVE_AD_SOURCE_ID] = normalized }
    }

    /** 保存用户广告总开关；SDK 是否启动仍由隐私状态决定。 */
    suspend fun setAdsGlobalEnabled(value: Boolean) {
        store.edit { preferences -> preferences[HostSettingsKeys.ADS_GLOBAL_ENABLED] = value }
    }

    /** 成组保存同意状态与政策版本，防止读取到跨版本的部分组合。 */
    suspend fun setConsent(state: String, policyVersion: String) {
        /** 仅接受白名单稳定 code；未知输入视为未同意。 */
        val normalizedState = normalizeConsentState(state)
        /** 已同意政策的版本号；不保存政策正文或地址。 */
        val normalizedVersion = policyVersion.trim()
        store.edit { preferences ->
            preferences[HostSettingsKeys.AD_CONSENT_STATE] = normalizedState
            preferences[HostSettingsKeys.AD_PRIVACY_POLICY_VERSION] = normalizedVersion
        }
    }

    /** 将原始键映射为广告决策可消费的安全快照。 */
    private fun mapSnapshot(preferences: Preferences): AdSettingsSnapshot {
        /** 广告源标识；空白输入统一视为自动选择。 */
        val sourceId = preferences[HostSettingsKeys.ACTIVE_AD_SOURCE_ID]?.trim()?.ifBlank { DEFAULT_ACTIVE_SOURCE_ID }
            ?: DEFAULT_ACTIVE_SOURCE_ID
        /** 广告总开关；无文件仍不赋予 SDK 隐私同意。 */
        val enabled = preferences[HostSettingsKeys.ADS_GLOBAL_ENABLED] ?: DEFAULT_ADS_GLOBAL_ENABLED
        /** 已保存的同意 code；未知值回退 unknown。 */
        val consent = normalizeConsentState(preferences[HostSettingsKeys.AD_CONSENT_STATE])
        /** 已保存的政策版本；只去掉首尾空白。 */
        val version = preferences[HostSettingsKeys.AD_PRIVACY_POLICY_VERSION]?.trim().orEmpty()
        return AdSettingsSnapshot(sourceId, enabled, consent, version)
    }

    /** 将外部输入收敛到现有同意状态白名单。 */
    private fun normalizeConsentState(value: String?): String {
        /** 去空白并统一大小写后的候选 code。 */
        val normalized = value?.trim()?.lowercase().orEmpty()
        return when (normalized) {
            "granted", "denied", "not_required" -> normalized
            else -> DEFAULT_CONSENT_STATE
        }
    }
}
