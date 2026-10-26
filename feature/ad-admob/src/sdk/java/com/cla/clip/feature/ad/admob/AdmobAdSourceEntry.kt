package com.cla.clip.feature.ad.admob

import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    /** 所有已注册位置都只提供原生广告，不引入激励或插屏格式。 */
    override val supportedPlacements = AdPlacement.entries.toSet()

    /** 全部 AdMob 自绘卡片在标题行内承载关闭控件，宿主不再追加描边外的按钮。 */
    override val rendersHostDismissControl = true

    /** 平台独立隐私语义与运行时安全边界都必须满足。 */
    override fun isAvailable(consentState: AdConsentState, runtimePolicy: AdRuntimePolicy): Boolean =
        runtimePolicy.adsGlobalEnabled && runtimePolicy.isMainProcess &&
            !runtimePolicy.isSensitiveContext && !runtimePolicy.remoteKillSwitchEnabled &&
            sourceId !in runtimePolicy.sessionDisabledSourceIds && privacyController.state.value.canRequestAds

    /** 页面拥有资源，组合只绑定 View；暂停和普通重组不创建新请求。 */
    @Composable
    override fun NativeAdSlot(request: AdSlotRequest, onEvent: (AdSlotEvent) -> Unit, modifier: Modifier) {
        // themed Context 仅在页面资源存活期间保留。
        val context = LocalContext.current
        // 主题同步不影响请求及缓存身份。
        val colors = MaterialTheme.colorScheme
        // 宿主生命周期也保护没有页面缓存所有者的兼容调用方。
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        // 事件和触摸读取最新许可，避免普通弹窗关闭后仍捕获旧门控。
        val latestRequest by rememberUpdatedState(request)
        // 每个资源所有者只创建一次 SDK 会话，Lazy 回滚从页面缓存取得原会话。
        val session = remember(request.requestNonce, request.resourceOwner) {
            // 工厂留在 adapter 内，宿主无法访问 NativeAd 和 Activity 引用。
            val create = { AdmobSlotSession(context, request, initializer, privacyController, unitId(request.placement), onEvent) }
            // 跨模块属性先读取快照，避免依赖不可用的 Kotlin smart cast。
            val resourceOwner = request.resourceOwner
            if (resourceOwner == null) create()
            else resourceOwner.getOrCreate(create) as? AdmobSlotSession
        } ?: return
        // 每次组合绑定拥有独立身份，旧 onDispose 不能解绑后来的挂载。
        val binding = remember(session) { Any() }
        // 仅前台且未遮挡才启动新工作；更新已加载素材不会重新请求。
        SideEffect {
            session.update(binding, request, onEvent)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && request.canRequest) session.start()
        }
        // PAUSE 不释放；STOP/DESTROY 即便没有后续组合帧也同步清理。
        DisposableEffect(session, lifecycle) {
            // 重复恢复由 session 的一次启动保护过滤，停止原因进入低敏日志。
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> session.close(AdReleaseReason.LifecycleStop)
                    Lifecycle.Event.ON_DESTROY -> session.close(AdReleaseReason.Destroyed)
                    Lifecycle.Event.ON_RESUME -> if (latestRequest.canRequest) session.start()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            // 缓存调用方离屏只解绑；兼容调用方仍由组合负责销毁。
            onDispose {
                lifecycle.removeObserver(observer)
                session.detach(binding)
                if (request.resourceOwner == null) session.close(AdReleaseReason.PageLeft)
            }
        }
        // 首次素材只在安全布局时揭示，已有 View 可跨离屏绑定保留。
        val nativeView = session.view
        if (nativeView != null && AdPresentationPolicy.canMount(true,
                request.contentPresentable && request.contentPresentableNow(), session.presented,
                request.canReveal && request.canRevealNow())) {
            AndroidView(
                // 每次挂载独享外壳；搬移 SDK View 前移除旧父节点，避免双重挂载。
                factory = { viewContext ->
                    AdmobNativeContainer(viewContext).apply {
                        session.bindView(binding, this)
                    }
                },
                modifier = modifier,
                // 外壳销毁只解除子 View 关系，素材销毁始终属于页面资源所有者。
                onRelease = { container ->
                    // 素材可能已搬到新外壳；旧 onRelease 只能清理自身或已解绑按钮的回调。
                    if (nativeView.parent == null || nativeView.parent === container) {
                        AdmobDismissControl.update(nativeView, false)
                    }
                    container.removeAllViews()
                    container.canInteract = { false }
                    container.updateInteraction(false)
                },
                // 暂停/弹窗不改广告外观，仅拦住触摸；SDK 原有点击注册保持完整。
                update = { container ->
                    // 绑定登记和 View 搬移统一处理，也能修复应用阶段中新旧外壳交接。
                    session.bindView(binding, container)
                    container.canInteract = {
                        latestRequest.contentPresentable && latestRequest.contentPresentableNow() &&
                            latestRequest.interactionEnabled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    }
                    container.updateInteraction(container.canInteract())
                    // 关闭属于宿主操作，事件发生时读取最新绑定，不进入 SDK 广告点击回调。
                    AdmobDismissControl.update(nativeView, container.canInteract()) {
                        // 无障碍等直接调用按钮也要读取实时交互门，不能绕过临时遮挡。
                        if (container.canInteract()) latestRequest.onDismiss()
                    }
                    AdmobNativeCardStyle.applyColors(
                        nativeView, colors.surface.toArgb(), colors.onSurface.toArgb(),
                        colors.onSurfaceVariant.toArgb(), colors.primary.toArgb(), colors.onPrimary.toArgb(),
                        outline = colors.outlineVariant.toArgb(),
                    )
                },
            )
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
