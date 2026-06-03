package com.cla.clip.feature.ad.csj

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cla.clip.feature.ad.api.AdConsentState
import com.cla.clip.feature.ad.api.AdPlacement
import com.cla.clip.feature.ad.api.AdRuntimePolicy
import com.cla.clip.feature.ad.api.AdSlotEvent
import com.cla.clip.feature.ad.api.AdSlotRequest
import com.cla.clip.feature.ad.api.AdSourceEntry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

/** 穿山甲详情页广告源入口。 */
@Singleton
internal class CsjAdSourceEntry @Inject constructor(
    /** 穿山甲构建配置提供者。 */
    private val configProvider: CsjAdConfigProvider,
    /** 穿山甲广告可用性策略。 */
    private val availabilityPolicy: CsjAdAvailabilityPolicy,
    /** 穿山甲广告隐私状态读取器。 */
    private val consentProvider: CsjAdConsentProvider,
    /** 穿山甲 SDK 懒初始化器。 */
    private val initializer: CsjAdInitializer,
    /** 穿山甲 SDK facade。 */
    private val sdkClient: CsjSdkClient,
) : AdSourceEntry {
    override val sourceId: String = CSJ_AD_SOURCE_ID

    override val priority: Int = CSJ_AD_PRIORITY

    override val supportedPlacements: Set<AdPlacement> = setOf(AdPlacement.DetailNative)

    /**
     * 判断穿山甲广告源是否可用。
     *
     * 这里只做同步规则判断；SDK 初始化在 NativeAdSlot 内按需懒执行。
     */
    override fun isAvailable(consentState: AdConsentState, runtimePolicy: AdRuntimePolicy): Boolean {
        /** 当前用户同意的广告隐私版本；用于防止旧隐私版本启动新增 SDK。 */
        val acceptedPrivacyPolicyVersion = consentProvider.privacyPolicyVersionFlow.value
        return availabilityPolicy.isAvailable(
            config = configProvider.config,
            consentState = consentState,
            acceptedPrivacyPolicyVersion = acceptedPrivacyPolicyVersion,
            runtimePolicy = runtimePolicy,
        )
    }

    /**
     * 渲染详情页穿山甲原生广告位。
     *
     * Composable 只负责收集隐私版本和接入生命周期；真实 SDK 请求与释放封装到内部 slot。
     */
    @Composable
    override fun NativeAdSlot(
        request: AdSlotRequest,
        onEvent: (AdSlotEvent) -> Unit,
        modifier: Modifier,
    ) {
        /** 当前广告隐私同意 code；撤回时内部 slot 会释放当前广告并停止新请求。 */
        val consentStateCode by consentProvider.consentStateFlow.collectAsStateWithLifecycle()
        /** 当前用户同意的广告隐私版本；版本过期时隐藏广告。 */
        val acceptedPrivacyPolicyVersion by consentProvider.privacyPolicyVersionFlow.collectAsStateWithLifecycle()
        CsjNativeAdSlot(
            request = request,
            config = configProvider.config,
            consentState = consentProvider.toAdConsentState(consentStateCode),
            acceptedPrivacyPolicyVersion = acceptedPrivacyPolicyVersion,
            initializer = initializer,
            sdkClient = sdkClient,
            availabilityPolicy = availabilityPolicy,
            onEvent = onEvent,
            modifier = modifier,
        )
    }
}

/** 穿山甲广告源 Hilt 绑定，只有宿主依赖本模块时才会进入广告源集合。 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class CsjAdSourceModule {
    /** 把穿山甲广告源加入广告源集合，供宿主按运行时策略选择。 */
    @Binds
    @IntoSet
    abstract fun bindCsjAdSourceEntry(impl: CsjAdSourceEntry): AdSourceEntry

    /** 把穿山甲 SDK facade 绑定为可替换接口，便于测试和 SDK 升级隔离。 */
    @Binds
    abstract fun bindCsjSdkClient(impl: CsjSdkDirectClient): CsjSdkClient
}
