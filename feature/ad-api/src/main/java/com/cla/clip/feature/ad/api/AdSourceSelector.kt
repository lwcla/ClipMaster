package com.cla.clip.feature.ad.api

import javax.inject.Inject

/**
 * 广告源选择器。
 *
 * 选择器只处理低敏策略：总开关、同意状态、显式 source、`auto` 优先级和会话保险丝；具体 SDK 初始化由 adapter 自行懒执行。
 */
class AdSourceSelector @Inject constructor() {

    /**
     * 根据当前广告位和运行时策略选择一个可用广告源。
     *
     * 未知显式 sourceId 会回退到 `auto`，避免旧配置或异常写入导致广告位永久不可用。
     */
    fun select(
        sources: Set<AdSourceEntry>,
        placement: AdPlacement,
        activeSourceId: String,
        consentState: AdConsentState,
        runtimePolicy: AdRuntimePolicy,
    ): AdSourceSelection {
        if (!runtimePolicy.adsGlobalEnabled) {
            return AdSourceSelection.Hidden(reasonCode = "ads_global_disabled")
        }

        if (runtimePolicy.remoteKillSwitchEnabled) {
            return AdSourceSelection.Hidden(reasonCode = "ads_remote_kill_switch")
        }

        /** 规范化后的 source 设置值；空白或异常值都按 auto 处理。 */
        val normalizedSourceId = AdSourceSelectionMode.normalize(activeSourceId)
        if (normalizedSourceId == AdSourceSelectionMode.OFF) {
            return AdSourceSelection.Hidden(reasonCode = "ads_source_off")
        }

        if (!runtimePolicy.isMainProcess) {
            return AdSourceSelection.Hidden(reasonCode = "ads_not_main_process")
        }

        if (runtimePolicy.isSensitiveContext) {
            return AdSourceSelection.Hidden(reasonCode = "ads_sensitive_context")
        }

        if (consentState == AdConsentState.Unknown || consentState == AdConsentState.Denied) {
            return AdSourceSelection.Hidden(reasonCode = "consent_unavailable")
        }

        /** 当前广告位可用的 source 候选，已过滤广告位支持、会话保险丝和 adapter 自身可用性。 */
        val availableSources = sources
            .filter { source ->
                placement in source.supportedPlacements &&
                    source.sourceId !in runtimePolicy.sessionDisabledSourceIds &&
                    source.isAvailable(consentState, runtimePolicy)
            }
            .sortedWith(compareByDescending<AdSourceEntry> { source -> source.priority }.thenBy { source -> source.sourceId })

        if (availableSources.isEmpty()) {
            return AdSourceSelection.Hidden(reasonCode = "no_available_source")
        }

        /** 显式匹配的 source；找不到时回退到 auto 的首个候选。 */
        val explicitSource = availableSources.firstOrNull { source -> source.sourceId == normalizedSourceId }
        return AdSourceSelection.Selected(source = explicitSource ?: availableSources.first())
    }
}

/** 广告源选择模式常量。 */
object AdSourceSelectionMode {
    /** 自动选择广告源。 */
    const val AUTO = "auto"

    /** 关闭全部广告源。 */
    const val OFF = "off"

    /**
     * 规范化持久化或远程下发的广告源设置。
     *
     * 空白值回退到 `auto`；其他非空值保留为显式 sourceId，由选择器决定是否能命中。
     */
    fun normalize(value: String?): String {
        /** 去掉首尾空白后的 source 设置值，避免配置误写空格导致命中失败。 */
        val trimmedValue = value?.trim().orEmpty()
        return trimmedValue.ifBlank { AUTO }
    }
}

/**
 * 广告源选择结果。
 *
 * 结果只携带低敏 source 或 reasonCode；隐藏原因可用于诊断，但不得包含 SDK 响应体或用户内容。
 */
sealed class AdSourceSelection {
    /** 已选中可渲染广告源。 */
    data class Selected(
        /** 当前要渲染的广告源。 */
        val source: AdSourceEntry,
    ) : AdSourceSelection()

    /** 当前广告位需要隐藏。 */
    data class Hidden(
        /** 低敏隐藏原因码。 */
        val reasonCode: String,
    ) : AdSourceSelection()
}
