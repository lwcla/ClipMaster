package com.cla.clip.feature.ad.admob

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.cla.clip.feature.ad.api.*
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

/** Hilt 只在依赖 AdMob 的变体中注册真实源，SDK 类型不跨模块泄露。 */
@Singleton
internal class AdmobAdSourceEntry @Inject constructor(
    /** 平台隐私控制器同时通过 API 暴露给宿主。 */
    override val privacyController: AdmobPrivacyController,
    /** 初始化器不在构造阶段启动 SDK。 */
    private val initializer: AdmobInitializer,
) : AdSourceEntry {
    /** 稳定源标识。 */
    override val sourceId = "admob"
    /** 显式启用真实源时优先于调试占位。 */
    override val priority = 200
    /** 16 个位置都只提供原生广告，不引入激励或插屏格式。 */
    override val supportedPlacements = AdPlacement.entries.toSet()

    /** 平台独立隐私语义与运行时安全边界都必须满足。 */
    override fun isAvailable(consentState: AdConsentState, runtimePolicy: AdRuntimePolicy): Boolean =
        runtimePolicy.adsGlobalEnabled && runtimePolicy.isMainProcess &&
            !runtimePolicy.isSensitiveContext && !runtimePolicy.remoteKillSwitchEnabled &&
            sourceId !in runtimePolicy.sessionDisabledSourceIds && privacyController.state.value.canRequestAds

    /** 只以 nonce 创建请求；布局门控和回调通过更新方法同步。 */
    @Composable
    override fun NativeAdSlot(request: AdSlotRequest, onEvent: (AdSlotEvent) -> Unit, modifier: Modifier) {
        /** 当前 Activity 的 themed Context，只在请求生命周期内保留。 */
        val context = LocalContext.current
        /** 素材归当前 nonce 所有，滚动门控变化不能创建新请求。 */
        val session = remember(request.requestNonce) {
            AdmobSlotSession(context, request, initializer, privacyController, unitId(request.placement), onEvent)
        }
        // 更新布局回调和展示许可不影响 SDK 请求身份。
        SideEffect { session.update(request, onEvent) }
        // 页面离开组合立即取消请求并释放素材；根页面控制可见生命周期。
        DisposableEffect(session) {
            session.start()
            onDispose { session.close() }
        }
        /** View 仅在真实素材绑定成功且允许展示之后非空。 */
        val nativeView = session.view
        if (nativeView != null) {
            AndroidView(factory = { nativeView }, modifier = modifier)
        }
    }

    /** 构建配置按位置固定映射，禁止把页面正文或 URL 传给 SDK。 */
    private fun unitId(placement: AdPlacement): String = when (placement) {
        AdPlacement.DetailNative -> BuildConfig.DetailNative
        AdPlacement.HomeFeedNative -> BuildConfig.HomeFeedNative
        AdPlacement.SearchFeedNative -> BuildConfig.SearchFeedNative
        AdPlacement.FoldedFeedNative -> BuildConfig.FoldedFeedNative
        AdPlacement.FoldedSearchNative -> BuildConfig.FoldedSearchNative
        AdPlacement.RecycleFeedNative -> BuildConfig.RecycleFeedNative
        AdPlacement.VideoExtractNative -> BuildConfig.VideoExtractNative
        AdPlacement.VideoDownloadProgressNative -> BuildConfig.VideoDownloadProgressNative
        AdPlacement.VideoDownloadResultNative -> BuildConfig.VideoDownloadResultNative
        AdPlacement.ImageExtractNative -> BuildConfig.ImageExtractNative
        AdPlacement.ImageDownloadProgressNative -> BuildConfig.ImageDownloadProgressNative
        AdPlacement.ImageDownloadResultNative -> BuildConfig.ImageDownloadResultNative
        AdPlacement.VideoHistoryNative -> BuildConfig.VideoHistoryNative
        AdPlacement.ImageHistoryNative -> BuildConfig.ImageHistoryNative
        AdPlacement.BackupOverviewNative -> BuildConfig.BackupOverviewNative
        AdPlacement.BackupRestoreNative -> BuildConfig.BackupRestoreNative
    }
}

/** 真实广告源使用已有 multibinding 插槽，默认包不生成此绑定。 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class AdmobAdModule {
    /** 将独立广告源加入宿主可选集合。 */
    @Binds
    @IntoSet
    abstract fun bindAdmobSource(source: AdmobAdSourceEntry): AdSourceEntry
}
