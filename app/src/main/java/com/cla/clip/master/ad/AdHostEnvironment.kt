package com.cla.clip.master.ad

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.cla.clip.base.general.config.ad.AdSettingsRepository
import com.cla.clip.base.general.config.ad.AdSettingsSnapshot
import com.cla.clip.feature.ad.api.*
import com.cla.clip.master.BuildConfig
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.StateFlow

/** 页面只读取低敏广告运行环境，不持有 SDK 或业务数据源。 */
internal data class AdHostEnvironment(
    /** 已编译来源，由宿主 Hilt 集合提供。 */
    val sources: Set<AdSourceEntry>,
    /** 共用来源选择策略。 */
    val selector: AdSourceSelector,
    /** 用户选择的来源代号。 */
    val activeSourceId: String,
    /** 国内来源的历史授权状态，Google 使用自己的 controller。 */
    val legacyConsent: AdConsentState,
    /** 主进程、总开关和保险丝的低敏快照。 */
    val policy: AdRuntimePolicy,
    /** 来源事件失败后的隔离回调。 */
    val disableSource: (String) -> Unit,
    /** 隐私快照使 UMP 变化触发 Compose 来源重新选择。 */
    val privacyStates: Map<String, AdPrivacyState>,
)

/** Preview 与未接入宿主默认无广告。 */
internal val LocalAdHostEnvironment = staticCompositionLocalOf<AdHostEnvironment?> { null }

/** Activity 外壳连接现有设置；只有可交互生命周期才更新平台隐私。 */
@Composable
internal fun AdHostProvider(
    sources: Set<AdSourceEntry>,
    selector: AdSourceSelector,
    fuse: AdSessionFailureFuse,
    isMainProcess: Boolean,
    adSettings: StateFlow<AdSettingsSnapshot?>,
    content: @Composable () -> Unit,
) {
    /** 仅收集当前页面已提交的本机配置；读取前和失败后保持安全默认。 */
    val settings by adSettings.collectAsStateWithLifecycle()
    /** 广告总开关在配置未就绪时必须为 false，阻止来源初始化。 */
    val enabled = settings?.adsGlobalEnabled ?: false
    /** 当前来源选择；未就绪时仅显示自动标识，不赋予广告资格。 */
    val sourceId = settings?.activeSourceId ?: AdSettingsRepository.DEFAULT_ACTIVE_SOURCE_ID
    /** 国内来源历史同意状态；未就绪时必须按 unknown 处理。 */
    val consentCode = settings?.consentState ?: AdSettingsRepository.DEFAULT_CONSENT_STATE
    // 本进程中初始化或渲染失败的来源名单。
    val disabledSources by fuse.disabledSourceIdsFlow.collectAsStateWithLifecycle()
    // Activity 仅在生命周期协程内传给隐私表单，不持久保存。
    val activity = LocalContext.current.adHostActivity()
    // Activity 生命周期控制隐私表单启动时机。
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // 固定来源顺序保证动态隐私收集的组合身份稳定。
    val snapshots = sources.sortedBy { it.sourceId }.associate { source ->
        // 每个来源可以提供自己的隐私控制器，国内来源保持空值。
        val controller = source.privacyController
        // 只收集低敏资格状态，避免页面持有平台同意内容。
        val snapshot = if (controller == null) AdPrivacyState() else controller.state.collectAsStateWithLifecycle().value
        LaunchedEffect(controller, activity, enabled, isMainProcess) {
            if (controller != null && activity != null && enabled && isMainProcess) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    controller.refresh(activity)
                    awaitCancellation()
                }
            }
        }
        source.sourceId to snapshot
    }
    // 历史国内同意保持原语义，不能映射成 Google 授权。
    val legacy = when (consentCode.trim().lowercase()) {
        "granted" -> AdConsentState.Granted
        "denied" -> AdConsentState.Denied
        "not_required" -> AdConsentState.NotRequired
        // 调试占位沿用旧详情页 NotRequired 兜底；真实国内来源仍要求 Granted。
        else -> if (BuildConfig.DEBUG) AdConsentState.NotRequired else AdConsentState.Unknown
    }
    // 每次低敏快照变化都向页面传播，关闭开关立即撤销来源资格。
    val environment = AdHostEnvironment(sources, selector, sourceId, legacy,
        AdRuntimePolicy(enabled, disabledSources, BuildConfig.DEBUG, isMainProcess), fuse::disableSource, snapshots)
    CompositionLocalProvider(LocalAdHostEnvironment provides environment, content = content)
}

/** 解包当前 UI 的 Activity；应用 Context 返回空，不强制类型转换。 */
internal fun Context.adHostActivity(): Activity? {
    // 每层只沿 baseContext 前进，防止不规范包装器自引用。
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        // Context 包装器的下一层，用于循环保护和 Activity 解包。
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
    return current as? Activity
}
