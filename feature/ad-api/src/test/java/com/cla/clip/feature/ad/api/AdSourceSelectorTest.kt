package com.cla.clip.feature.ad.api

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 广告源选择器测试，覆盖关闭、同意、保险丝、显式 source 和 auto 优先级。 */
class AdSourceSelectorTest {
    /** 被测选择器；每个测试只依赖纯输入输出，不需要 Android runtime。 */
    private val selector = AdSourceSelector()

    /** 默认运行时策略；广告总开关开启、没有保险丝禁用源、按非调试请求处理。 */
    private val defaultPolicy = AdRuntimePolicy(adsGlobalEnabled = true)

    /** 空广告源集合时应隐藏广告位。 */
    @Test
    fun selectHidesWhenSourceSetIsEmpty() {
        /** 当前选择结果；空集合没有可用 adapter。 */
        val selection = selector.select(
            sources = emptySet(),
            placement = AdPlacement.DetailNative,
            activeSourceId = AdSourceSelectionMode.AUTO,
            consentState = AdConsentState.NotRequired,
            runtimePolicy = defaultPolicy,
        )

        assertHidden(selection, "no_available_source")
    }

    /** 全局广告关闭时应优先隐藏，不继续触发 adapter 可用性判断。 */
    @Test
    fun selectHidesWhenGlobalSwitchIsDisabled() {
        /** 当前选择结果；全局关闭优先级高于任何 source。 */
        val selection = selector.select(
            sources = setOf(FakeAdSource(sourceId = "debug", priority = 1)),
            placement = AdPlacement.DetailNative,
            activeSourceId = AdSourceSelectionMode.AUTO,
            consentState = AdConsentState.NotRequired,
            runtimePolicy = AdRuntimePolicy(adsGlobalEnabled = false),
        )

        assertHidden(selection, "ads_global_disabled")
    }

    /** 远程保险开关命中时应隐藏广告，且不能继续选择具体 SDK。 */
    @Test
    fun selectHidesWhenRemoteKillSwitchIsEnabled() {
        /** 当前选择结果；远程开关只能降级或关闭广告。 */
        val selection = selector.select(
            sources = setOf(FakeAdSource(sourceId = "csj", priority = 100)),
            placement = AdPlacement.DetailNative,
            activeSourceId = AdSourceSelectionMode.AUTO,
            consentState = AdConsentState.NotRequired,
            runtimePolicy = AdRuntimePolicy(
                adsGlobalEnabled = true,
                remoteKillSwitchEnabled = true,
            ),
        )

        assertHidden(selection, "ads_remote_kill_switch")
    }

    /** 用户选择 off 时应隐藏广告位。 */
    @Test
    fun selectHidesWhenSourceModeIsOff() {
        /** 当前选择结果；off 表示本机显式关闭广告。 */
        val selection = selector.select(
            sources = setOf(FakeAdSource(sourceId = "debug", priority = 1)),
            placement = AdPlacement.DetailNative,
            activeSourceId = AdSourceSelectionMode.OFF,
            consentState = AdConsentState.NotRequired,
            runtimePolicy = defaultPolicy,
        )

        assertHidden(selection, "ads_source_off")
    }

    /** 非主进程不允许选择真实广告源，避免辅助进程误初始化 SDK。 */
    @Test
    fun selectHidesWhenCurrentProcessIsNotMainProcess() {
        /** 当前选择结果；进程边界由宿主传入运行时策略。 */
        val selection = selector.select(
            sources = setOf(FakeAdSource(sourceId = "csj", priority = 100)),
            placement = AdPlacement.DetailNative,
            activeSourceId = AdSourceSelectionMode.AUTO,
            consentState = AdConsentState.NotRequired,
            runtimePolicy = AdRuntimePolicy(
                adsGlobalEnabled = true,
                isMainProcess = false,
            ),
        )

        assertHidden(selection, "ads_not_main_process")
    }

    /** 敏感详情上下文应隐藏广告，且隐藏原因不能包含原始内容。 */
    @Test
    fun selectHidesWhenSensitiveContextIsDetected() {
        /** 当前选择结果；只传布尔标记，不把剪贴正文传进选择器或 adapter。 */
        val selection = selector.select(
            sources = setOf(FakeAdSource(sourceId = "csj", priority = 100)),
            placement = AdPlacement.DetailNative,
            activeSourceId = AdSourceSelectionMode.AUTO,
            consentState = AdConsentState.NotRequired,
            runtimePolicy = AdRuntimePolicy(
                adsGlobalEnabled = true,
                isSensitiveContext = true,
            ),
        )

        assertHidden(selection, "ads_sensitive_context")
    }

    /** 同意状态不可用时应隐藏真实广告请求。 */
    @Test
    fun selectHidesWhenConsentIsDenied() {
        /** 当前选择结果；拒绝非必要信息处理时广告不可展示。 */
        val selection = selector.select(
            sources = setOf(FakeAdSource(sourceId = "debug", priority = 1)),
            placement = AdPlacement.DetailNative,
            activeSourceId = AdSourceSelectionMode.AUTO,
            consentState = AdConsentState.Denied,
            runtimePolicy = defaultPolicy,
        )

        assertHidden(selection, "consent_unavailable")
    }

    /** auto 模式应选择优先级最高的可用 source。 */
    @Test
    fun selectAutoChoosesHighestPrioritySource() {
        /** 低优先级广告源，用于确认 auto 不按集合原始顺序选择。 */
        val lowPrioritySource = FakeAdSource(sourceId = "low", priority = 1)
        /** 高优先级广告源，应成为最终选择结果。 */
        val highPrioritySource = FakeAdSource(sourceId = "high", priority = 10)

        /** 当前选择结果；auto 应按 priority 降序选择。 */
        val selection = selector.select(
            sources = setOf(lowPrioritySource, highPrioritySource),
            placement = AdPlacement.DetailNative,
            activeSourceId = AdSourceSelectionMode.AUTO,
            consentState = AdConsentState.NotRequired,
            runtimePolicy = defaultPolicy,
        )

        assertSelected(selection, highPrioritySource)
    }

    /** 显式 sourceId 命中可用 source 时应优先使用显式选择。 */
    @Test
    fun selectExplicitSourceWhenAvailable() {
        /** auto 下优先级更高的广告源；显式选择时不应被选中。 */
        val highPrioritySource = FakeAdSource(sourceId = "high", priority = 10)
        /** 用户显式选择的广告源；即使优先级较低也应命中。 */
        val explicitSource = FakeAdSource(sourceId = "explicit", priority = 1)

        /** 当前选择结果；显式 sourceId 应覆盖 auto 优先级。 */
        val selection = selector.select(
            sources = setOf(highPrioritySource, explicitSource),
            placement = AdPlacement.DetailNative,
            activeSourceId = "explicit",
            consentState = AdConsentState.NotRequired,
            runtimePolicy = defaultPolicy,
        )

        assertSelected(selection, explicitSource)
    }

    /** 显式 sourceId 不存在时应回退到 auto。 */
    @Test
    fun selectFallsBackToAutoWhenExplicitSourceIsMissing() {
        /** auto 回退时可用的广告源。 */
        val fallbackSource = FakeAdSource(sourceId = "fallback", priority = 3)

        /** 当前选择结果；未知 sourceId 不应让广告永久隐藏。 */
        val selection = selector.select(
            sources = setOf(fallbackSource),
            placement = AdPlacement.DetailNative,
            activeSourceId = "missing",
            consentState = AdConsentState.NotRequired,
            runtimePolicy = defaultPolicy,
        )

        assertSelected(selection, fallbackSource)
    }

    /** 保险丝禁用的 source 应被跳过。 */
    @Test
    fun selectSkipsSessionDisabledSource() {
        /** 原本优先级更高但已被保险丝禁用的广告源。 */
        val disabledSource = FakeAdSource(sourceId = "disabled", priority = 10)
        /** 保险丝禁用后应回退到的广告源。 */
        val fallbackSource = FakeAdSource(sourceId = "fallback", priority = 1)

        /** 当前选择结果；禁用集合来自当前会话保险丝。 */
        val selection = selector.select(
            sources = setOf(disabledSource, fallbackSource),
            placement = AdPlacement.DetailNative,
            activeSourceId = AdSourceSelectionMode.AUTO,
            consentState = AdConsentState.NotRequired,
            runtimePolicy = AdRuntimePolicy(
                adsGlobalEnabled = true,
                sessionDisabledSourceIds = setOf("disabled"),
            ),
        )

        assertSelected(selection, fallbackSource)
    }

    /** 不支持当前广告位的 source 应被过滤。 */
    @Test
    fun selectSkipsUnsupportedPlacement() {
        /** 不支持任何广告位的 source，用于确认 placement 过滤生效。 */
        val unsupportedSource = FakeAdSource(
            sourceId = "unsupported",
            priority = 10,
            supportedPlacements = emptySet(),
        )

        /** 当前选择结果；没有支持详情页广告位的 source。 */
        val selection = selector.select(
            sources = setOf(unsupportedSource),
            placement = AdPlacement.DetailNative,
            activeSourceId = AdSourceSelectionMode.AUTO,
            consentState = AdConsentState.NotRequired,
            runtimePolicy = defaultPolicy,
        )

        assertHidden(selection, "no_available_source")
    }

    /** 断言选择结果为指定隐藏原因。 */
    private fun assertHidden(selection: AdSourceSelection, expectedReason: String) {
        assertTrue(selection is AdSourceSelection.Hidden)
        /** 隐藏结果；前一行断言保证这里可以安全转换。 */
        val hiddenSelection = selection as AdSourceSelection.Hidden
        assertEquals(expectedReason, hiddenSelection.reasonCode)
    }

    /** 断言选择结果为指定广告源。 */
    private fun assertSelected(selection: AdSourceSelection, expectedSource: AdSourceEntry) {
        assertTrue(selection is AdSourceSelection.Selected)
        /** 选中结果；前一行断言保证这里可以安全转换。 */
        val selectedSelection = selection as AdSourceSelection.Selected
        assertSame(expectedSource, selectedSelection.source)
    }
}

/** 测试专用广告源，只暴露选择器需要的低敏属性。 */
private data class FakeAdSource(
    /** 测试广告源 ID。 */
    override val sourceId: String,
    /** 测试广告源优先级。 */
    override val priority: Int,
    /** 测试广告源支持的广告位集合。 */
    override val supportedPlacements: Set<AdPlacement> = setOf(AdPlacement.DetailNative),
    /** 测试广告源自身可用性。 */
    private val available: Boolean = true,
) : AdSourceEntry {
    /** 返回测试构造时指定的可用性，不额外模拟 SDK 初始化。 */
    override fun isAvailable(consentState: AdConsentState, runtimePolicy: AdRuntimePolicy): Boolean = available

    /** 测试不需要实际渲染 Compose 广告位。 */
    @Composable
    override fun NativeAdSlot(request: AdSlotRequest, onEvent: (AdSlotEvent) -> Unit, modifier: Modifier) = Unit
}
