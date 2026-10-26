package com.cla.clip.feature.ad.api

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 广告位枚举。
 *
 * 每个枚举值都对应宿主中一个稳定广告位置，后续新增列表或搜索广告位时必须在主方案文档中补充频控和隐私边界。
 */
enum class AdPlacement(
    /** 低敏广告位 ID，只用于选择器、日志和调试展示，不包含页面内容或剪贴数据。 */
    val placementId: String,
) {
    /** 详情页正文和操作区之间的信息流/原生广告位。 */
    DetailNative("detail_native"),
    /** 首页列表广告位。 */
    HomeFeedNative("home_feed_native"),
    /** 搜索结果列表广告位。 */
    SearchFeedNative("search_feed_native"),
    /** 折叠列表广告位。 */
    FoldedFeedNative("folded_feed_native"),
    /** 折叠内搜索广告位。 */
    FoldedSearchNative("folded_search_native"),
    /** 回收站列表广告位。 */
    RecycleFeedNative("recycle_feed_native"),
    /** 视频提取广告位。 */
    VideoExtractNative("video_extract_native"),
    /** 视频下载进度广告位。 */
    VideoDownloadProgressNative("video_download_progress_native"),
    /** 视频下载结果广告位。 */
    VideoDownloadResultNative("video_download_result_native"),
    /** 图片提取广告位。 */
    ImageExtractNative("image_extract_native"),
    /** 图片下载进度广告位。 */
    ImageDownloadProgressNative("image_download_progress_native"),
    /** 图片下载结果广告位。 */
    ImageDownloadResultNative("image_download_result_native"),
    /** 备份总览广告位。 */
    BackupOverviewNative("backup_overview_native"),
    /** 备份恢复广告位。 */
    BackupRestoreNative("backup_restore_native"),
}

/**
 * 广告同意状态。
 *
 * 调试占位源可使用 `NotRequired`；国内 SDK 沿用此状态，独立平台由 AdPrivacyController 决定是否可请求。
 */
enum class AdConsentState {
    /** 当前构建或广告源不需要额外广告同意即可渲染，例如内部调试占位。 */
    NotRequired,

    /** 用户已经同意广告 SDK 所需的非必要个人信息处理。 */
    Granted,

    /** 用户尚未完成旧同意选择，使用该旧协议的 SDK adapter 必须保持不可用。 */
    Unknown,

    /** 用户拒绝非必要个人信息处理，广告位应隐藏或降级。 */
    Denied,
}

/**
 * 广告运行时策略。
 *
 * 宿主通过该对象把已提交的全局开关、会话保险丝和调试状态传给选择器及广告源，避免 adapter 直接读取存储层。
 */
data class AdRuntimePolicy(
    /** 广告总开关；关闭时所有广告位直接隐藏，不允许 adapter 自行绕过。 */
    val adsGlobalEnabled: Boolean,
    /** 当前会话已被保险丝禁用的广告源 ID 集合，用于跳过连续失败或初始化异常的 source。 */
    val sessionDisabledSourceIds: Set<String> = emptySet(),
    /** 当前是否为调试构建或内部调试请求，用于区分测试广告位和正式广告位。 */
    val debugMode: Boolean = false,
    /** 当前进程是否为宿主主进程；真实 SDK 只能在主进程初始化，辅助进程必须直接不可用。 */
    val isMainProcess: Boolean = true,
    /** 预留远程广告能力开关；远程策略只能关闭或降级广告，不能绕过隐私同意。 */
    val remoteKillSwitchEnabled: Boolean = false,
)

/**
 * 单次广告位请求。
 *
 * 请求对象只携带低敏广告上下文；禁止加入剪贴正文、完整 URL、搜索词、本地文件路径或 SDK 原始响应。
 */
data class AdSlotRequest(
    /** 当前要渲染的广告位。 */
    val placement: AdPlacement,
    /** 详情页生命周期内生成的一次性请求标识，只用于去重和释放，不反推用户内容。 */
    val requestNonce: String,
    /** 当前请求是否只能使用测试广告配置。 */
    val isDebugRequest: Boolean,
    /** 是否允许把已加载素材发布到布局；变化不生成新请求。 */
    val canReveal: Boolean = true,
    /** 广告布局尺寸变化前通知宿主保存列表锚点；回调变化不生成新请求。 */
    val onLayoutWillChange: () -> Unit = {},
    /** 广告布局尺寸变化提交后通知宿主恢复锚点；回调变化不生成新请求。 */
    val onLayoutChanged: () -> Unit = {},
    /** 展示提交前同步读取交互许可，堵住指针事件早于重组的时间差；变化不生成新请求。 */
    val canRevealNow: () -> Boolean = { canReveal },
    /** 页面持有的有限缓存槽；为空时 adapter 沿用组合自身管理资源。 */
    val resourceOwner: AdSlotResourceOwner? = null,
    /** 暂停或临时遮挡时禁止点击，已展示布局与素材保持不变。 */
    val interactionEnabled: Boolean = true,
    /** 仅控制尚未启动的网络请求；缓存回绑可以继续展示原有素材。 */
    val canRequest: Boolean = true,
    /** 当前页面实际有可展示内容；持续影响 View 挂载，不阻止独立素材请求或释放缓存。 */
    val contentPresentable: Boolean = true,
    /** 异步加载和旧绑定回调必须读取最新内容资格，不能用加载时的快照回挂空态 View。 */
    val contentPresentableNow: () -> Boolean = { contentPresentable },
    /** 卡片内部关闭控件调用宿主既有释放入口；仅编译期回调，不传给广告 SDK 或持久化。 */
    val onDismiss: () -> Unit = {},
)

/**
 * 广告事件类型。
 *
 * 事件只用于低敏诊断和后续收益评估；真实 adapter 不得把 SDK 原始响应或广告素材塞进事件。
 */
enum class AdSlotEventType(
    /** 稳定事件 code，用于日志、测试和后续聚合统计。 */
    val eventCode: String,
) {
    /** 广告位开始向 source 发起请求。 */
    RequestStarted("request_started"),

    /** 广告素材已加载完成。 */
    Loaded("loaded"),

    /** 广告已经产生展示。 */
    Impression("impression"),

    /** 用户点击了广告素材。 */
    Clicked("clicked"),

    /** 当前 source 没有填充广告，详情页本次不再重试。 */
    NoFill("no_fill"),

    /** 当前 source 加载失败，详情页本次不再重试。 */
    LoadFailed("load_failed"),

    /** 当前 source 初始化失败，需要触发会话级保险丝。 */
    InitializationFailed("initialization_failed"),

    /** 当前 source 渲染失败，需要触发会话级保险丝。 */
    RenderFailed("render_failed"),

    /** 广告位离开页面或被释放。 */
    Released("released"),
}

/**
 * 低敏广告事件。
 *
 * 所有字段都必须保持低敏；禁止记录剪贴正文、完整 URL、query、设备标识、Cookie、Token、广告素材和 SDK 原始响应。
 */
data class AdSlotEvent(
    /** 产生事件的广告源 ID。 */
    val providerId: String,
    /** 产生事件的广告位 ID。 */
    val placementId: String,
    /** 当前事件类型。 */
    val eventType: AdSlotEventType,
    /** 脱敏原因码，空字符串表示没有额外原因。 */
    val reasonCode: String = "",
    /** 当前阶段耗时，未知时为 null，单位毫秒。 */
    val durationMs: Long? = null,
    /** 是否来自调试广告请求。 */
    val isDebug: Boolean = false,
    /** 广告 SDK 版本号；只允许稳定版本字符串，未知时为空，禁止包含设备或广告位信息。 */
    val sdkVersion: String = "",
)

/**
 * 广告源入口。
 *
 * 具体广告 SDK adapter 只实现该接口并通过 Hilt multibinding 暴露给宿主，宿主不直接依赖任何 SDK 类型。
 */
interface AdSourceEntry {
    /** 独立隐私平台；为空时继续使用旧的国内同意状态。 */
    val privacyController: AdPrivacyController? get() = null

    /** 自绘来源在标题行承载宿主关闭按钮时为 true，宿主不再追加外部控制行。 */
    val rendersHostDismissControl: Boolean get() = false

    /** 稳定广告源 ID，必须使用固定字符串，不能依赖枚举名或类名。 */
    val sourceId: String

    /** `auto` 模式下的优先级，数值越大越优先。 */
    val priority: Int

    /** 当前 source 支持的广告位集合。 */
    val supportedPlacements: Set<AdPlacement>

    /**
     * 判断当前 source 在运行时是否可用。
     *
     * 实现需要同时考虑隐私同意、渠道策略、广告总开关、会话保险丝、测试广告位和 SDK 初始化前置条件。
     */
    fun isAvailable(consentState: AdConsentState, runtimePolicy: AdRuntimePolicy): Boolean

    /**
     * 渲染原生广告位。
     *
     * 真实 SDK 若使用 Android View，必须在实现内部通过生命周期感知方式释放 View、loader、监听器和回调。
     */
    @Composable
    fun NativeAdSlot(
        request: AdSlotRequest,
        onEvent: (AdSlotEvent) -> Unit,
        modifier: Modifier,
    )
}
