package com.cla.clip.feature.ad.debug

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cla.clip.feature.ad.api.AdConsentState
import com.cla.clip.feature.ad.api.AdPlacement
import com.cla.clip.feature.ad.api.AdRuntimePolicy
import com.cla.clip.feature.ad.api.AdSlotEvent
import com.cla.clip.feature.ad.api.AdSlotEventType
import com.cla.clip.feature.ad.api.AdSlotRequest
import com.cla.clip.feature.ad.api.AdSourceEntry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

/** 调试广告源 ID，稳定用于本地选择、日志和测试。 */
private const val DEBUG_AD_SOURCE_ID = "debug"

/**
 * 调试广告源。
 *
 * 该实现不接入任何真实广告 SDK，只在调试请求下渲染占位 UI，用于验证详情页广告位、选择器和保险丝接线。
 */
@Singleton
internal class DebugAdSourceEntry @Inject constructor() : AdSourceEntry {
    override val sourceId: String = DEBUG_AD_SOURCE_ID

    override val priority: Int = 0

    override val supportedPlacements: Set<AdPlacement> = setOf(AdPlacement.DetailNative)

    /**
     * 判断调试广告是否可用。
     *
     * 只有调试请求才允许展示，避免调试模块被误打入正式包时显示占位广告。
     */
    override fun isAvailable(consentState: AdConsentState, runtimePolicy: AdRuntimePolicy): Boolean {
        return runtimePolicy.adsGlobalEnabled && runtimePolicy.debugMode
    }

    /**
     * 渲染详情页调试原生广告位。
     *
     * 占位 UI 只展示固定调试文案，不读取剪贴正文、链接、搜索词或任何用户内容。
     */
    @Composable
    override fun NativeAdSlot(
        request: AdSlotRequest,
        onEvent: (AdSlotEvent) -> Unit,
        modifier: Modifier,
    ) {
        LaunchedEffect(request.requestNonce) {
            onEvent(request.toDebugEvent(AdSlotEventType.RequestStarted))
            onEvent(request.toDebugEvent(AdSlotEventType.Loaded))
            onEvent(request.toDebugEvent(AdSlotEventType.Impression))
        }

        DisposableEffect(request.requestNonce) {
            onDispose {
                onEvent(request.toDebugEvent(AdSlotEventType.Released))
            }
        }

        DebugNativeAdCard(modifier = modifier)
    }
}

/**
 * 调试原生广告卡片。
 *
 * 视觉上使用低对比边框和简短 badge，让内部测试能确认广告位位置，同时不伪装成真实广告素材。
 */
@Composable
private fun DebugNativeAdCard(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.ad_debug_detail_native_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.ad_debug_detail_native_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = stringResource(R.string.ad_debug_detail_native_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 将调试广告请求转为低敏事件。
 *
 * 事件只包含 source、placement、类型和调试标记，避免调试实现成为后续真实 SDK 的隐私坏例子。
 */
private fun AdSlotRequest.toDebugEvent(eventType: AdSlotEventType): AdSlotEvent {
    return AdSlotEvent(
        providerId = DEBUG_AD_SOURCE_ID,
        placementId = placement.placementId,
        eventType = eventType,
        isDebug = isDebugRequest,
    )
}

/** 调试广告源 Hilt 绑定，只有宿主依赖本模块时才会进入广告源集合。 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DebugAdSourceModule {
    /** 把调试广告源加入广告源集合，供宿主按运行时策略选择。 */
    @Binds
    @IntoSet
    abstract fun bindDebugAdSourceEntry(impl: DebugAdSourceEntry): AdSourceEntry
}
