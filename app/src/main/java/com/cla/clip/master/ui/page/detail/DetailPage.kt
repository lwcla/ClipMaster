package com.cla.clip.master.ui.page.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cla.clip.base.general.R
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.utils.LinkUtils
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logW
import com.cla.clip.base.general.widget.DeleteIconButton
import com.cla.clip.feature.ad.api.AdConsentState
import com.cla.clip.feature.ad.api.AdPlacement
import com.cla.clip.feature.ad.api.AdRuntimePolicy
import com.cla.clip.feature.ad.api.AdSlotEvent
import com.cla.clip.feature.ad.api.AdSlotEventType
import com.cla.clip.feature.ad.api.AdSlotRequest
import com.cla.clip.feature.ad.api.AdSourceEntry
import com.cla.clip.feature.ad.api.AdSourceSelection
import com.cla.clip.feature.ad.api.AdSourceSelector
import com.cla.clip.feature.magnet.api.MagnetFeatureEntry
import com.cla.clip.master.ad.DetailAdSensitivityPolicy
import com.cla.clip.master.ui.dialog.ClipDeleteChoiceDialog
import com.cla.clip.master.ui.navigation.ImageExtractRoute
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.navigation.VideoExtractRoute
import com.cla.clip.master.ui.widget.ClipMasterCard
import com.cla.clip.master.ui.widget.SecondaryPageScaffold
import com.cla.clip.master.ui.theme.ClipMasterThemeTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import java.net.URI
import java.util.Locale
import java.util.UUID

/** 详情页底部默认直接展示的链接数量，更多链接进入选择弹层，避免底部区域膨胀。 */
private const val DETAIL_LINK_PREVIEW_LIMIT = 3

/** 链接摘要最多展示的 path 段数，query 默认不展示以降低视觉噪声和敏感参数暴露。 */
private const val DETAIL_LINK_SUMMARY_PATH_SEGMENT_LIMIT = 2

/** 无法解析 host 时原始链接摘要的最大长度，防止异常输入撑开详情页底部。 */
private const val DETAIL_LINK_RAW_SUMMARY_MAX_LENGTH = 48

/** 单个 path 片段的最大展示长度，过长文件名或 slug 会被省略。 */
private const val DETAIL_LINK_PATH_SEGMENT_MAX_LENGTH = 24

/** 详情页广告日志标签，只输出低敏 source、placement、事件和 reasonCode。 */
private const val DETAIL_AD_TAG = "DetailAdSlot"

/**
 * 详情页内部链接展示模型。
 *
 * 完整 URL 只用于复制和导航；摘要和类型用于多链接场景下帮助用户辨认目标。
 */
internal data class DetailLinkUiState(
    /** 原始完整 URL，复制和图片/视频提取必须使用该值，不能使用摘要。 */
    val url: String,
    /** 隐藏 query 后的短摘要，用于详情页底部和弹层展示。 */
    val summary: String,
    /** 链接资源类型，决定用户看到的轻量类型提示。 */
    val type: DetailLinkType,
    /** 是否允许进入公网图片/视频提取流程；非公网或非 http/https 链接只能复制。 */
    val canExtract: Boolean,
)

/** 详情页多链接选择时展示的轻量链接类型。 */
internal enum class DetailLinkType {
    /** 图片资源直链。 */
    Image,

    /** 音视频或流媒体资源直链。 */
    Media,

    /** 普通公网网页链接。 */
    Web,

    /** 其他可复制但不适合提取的链接，例如 file、ftp 或内网地址。 */
    Other,
}

/**
 * 剪贴详情页。
 *
 * 页面根据路由传入的 `clipId` 加载单条剪贴记录，点击正文卡片复制内容，并提供删除以及跳转到图片/视频提取的入口。
 * 数据读取和剪贴操作放在 ViewModel 中，Composable 只负责生命周期触发和状态渲染。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailPage(
    detailVm: DetailViewModel = hiltViewModel(),
    clipId: Long,
    onBack: () -> Unit,
    onNavigate: (Route) -> Unit,  // 跳转页面
    magnetFeatures: Set<MagnetFeatureEntry> = emptySet(),
    onOpenMagnetSearch: (MagnetFeatureEntry, String) -> Unit = { _, _ -> },
    adSources: Set<AdSourceEntry> = emptySet(),
    adSourceSelector: AdSourceSelector = remember { AdSourceSelector() },
    activeAdSourceIdFlow: StateFlow<String> = remember { MutableStateFlow("auto") },
    adsGlobalEnabledFlow: StateFlow<Boolean> = remember { MutableStateFlow(true) },
    adConsentStateFlow: StateFlow<String> = remember { MutableStateFlow("not_required") },
    adPrivacyPolicyVersionFlow: StateFlow<String> = remember { MutableStateFlow("") },
    adDisabledSourceIdsFlow: StateFlow<Set<String>> = remember { MutableStateFlow(emptySet()) },
    isMainProcess: Boolean = true,
    detailAdSensitivityPolicy: DetailAdSensitivityPolicy = remember { DetailAdSensitivityPolicy() },
    onDisableAdSource: (String) -> Unit = {},
) {
    /** 当前等待删除确认的剪贴记录；为 null 时不显示删除选择弹窗。 */
    var deleteClip by remember { mutableStateOf<ClipShowEntity?>(null) }

    // 只在 clipId 变化时触发加载，避免每次重组都查库。
    LaunchedEffect(clipId) { detailVm.loadClip(clipId) }
    val uiState = detailVm.clipFlow.collectAsStateWithLifecycle().value
    /** 当前已成功加载的剪贴详情；只有成功态才在标题栏展示低频删除入口。 */
    val loadedClip = (uiState as? DetailUiState.Success)?.clip

    LaunchedEffect(Unit) {
        detailVm.deleteSuccessFlow.collectLatest {
            // 删除成功后返回上一页，避免详情页继续展示已删除记录。
            onBack()
        }
    }

    SecondaryPageScaffold(
        title = stringResource(R.string.base_general_clip_detail),
        onBack = onBack,
        actions = {
            loadedClip?.let { clip ->
                DeleteIconButton(
                    contentDescription = stringResource(R.string.base_general_delete),
                    onClick = {
                        // 标题栏删除入口只打开现有二次确认弹窗，不直接执行危险操作。
                        deleteClip = clip
                    }
                )
            }
        }
    ) { paddingValues ->
        when (uiState) {
            is DetailUiState.Loading -> {
                ClipMasterCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(paddingValues)
                        .padding(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.base_general_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    )
                }
            }

            is DetailUiState.Error -> {
                ClipMasterCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(paddingValues)
                        .padding(12.dp)
                ) {
                    Row {
                        Icon(
                            painter = rememberVectorPainter(Icons.Default.Error),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .padding(12.dp)
                                .size(24.dp)
                        )

                        Text(
                            text = uiState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 0.dp, 12.dp, end = 12.dp, bottom = 12.dp)
                        )
                    }
                }
            }

            is DetailUiState.Success -> {
                /** 当前成功加载的剪贴记录；正文卡片点击和底部能力入口都围绕这条记录工作。 */
                val clip = uiState.clip
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        // 正文可能非常长，Card 内部滚动可以保留顶部标题和底部能力入口的稳定位置。
                        ClipMasterCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                // 正文卡片轻点复用通用复制能力，同时保持时间戳刷新和复制提示一致。
                                detailVm.copyToClipboard(clip)
                            }
                        ) {
                            Text(
                                text = clip.content,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                    }

                    DetailActionSections(
                        detailVm = detailVm,
                        onNavigate = onNavigate,
                        magnetFeatures = magnetFeatures,
                        onOpenMagnetSearch = onOpenMagnetSearch,
                        clip = clip,
                        adSources = adSources,
                        adSourceSelector = adSourceSelector,
                        activeAdSourceIdFlow = activeAdSourceIdFlow,
                        adsGlobalEnabledFlow = adsGlobalEnabledFlow,
                        adConsentStateFlow = adConsentStateFlow,
                        adPrivacyPolicyVersionFlow = adPrivacyPolicyVersionFlow,
                        adDisabledSourceIdsFlow = adDisabledSourceIdsFlow,
                        isMainProcess = isMainProcess,
                        detailAdSensitivityPolicy = detailAdSensitivityPolicy,
                        onDisableAdSource = onDisableAdSource,
                    )
                }
            }
        }
    }

    ClipDeleteChoiceDialog(
        clip = deleteClip,
        onDismiss = { deleteClip = null },
        onMoveToRecycleBin = { clip -> detailVm.deleteClip(clip, sendEvent = true) },
        onDeletePermanently = { clip -> detailVm.deleteClipPermanently(clip, sendEvent = true) }
    )
}

/**
 * 详情页广告位。
 *
 * 广告位只在成功加载剪贴记录后出现，不读取剪贴正文、链接或搜索词；同一个 `clipId` 生命周期内复用同一个请求标识。
 */
@Composable
private fun DetailAdSlot(
    clipId: Long,
    isSensitiveContext: Boolean,
    adSources: Set<AdSourceEntry>,
    adSourceSelector: AdSourceSelector,
    activeAdSourceIdFlow: StateFlow<String>,
    adsGlobalEnabledFlow: StateFlow<Boolean>,
    adConsentStateFlow: StateFlow<String>,
    adPrivacyPolicyVersionFlow: StateFlow<String>,
    adDisabledSourceIdsFlow: StateFlow<Set<String>>,
    isMainProcess: Boolean,
    onDisableAdSource: (String) -> Unit,
) {
    /** 当前广告源选择，来自本机设置或未来远程策略；只包含 sourceId，不包含用户内容。 */
    val activeAdSourceId = activeAdSourceIdFlow.collectAsStateWithLifecycle().value
    /** 广告总开关；关闭时选择器会隐藏所有广告位。 */
    val adsGlobalEnabled = adsGlobalEnabledFlow.collectAsStateWithLifecycle().value
    /** 当前广告隐私同意状态 code；真实 SDK 只有 granted 才可能展示。 */
    val adConsentStateCode = adConsentStateFlow.collectAsStateWithLifecycle().value
    /** 用户同意的广告隐私政策版本；当前详情页只负责生命周期收集，真实版本匹配由 adapter 内部读取同一来源。 */
    adPrivacyPolicyVersionFlow.collectAsStateWithLifecycle().value
    /** 当前会话被保险丝禁用的广告源集合。 */
    val disabledSourceIds = adDisabledSourceIdsFlow.collectAsStateWithLifecycle().value
    /** 当前详情页生命周期内的广告请求标识；clipId 变化时重新生成，避免旧请求污染新详情。 */
    val requestNonce = rememberSaveable(clipId) { UUID.randomUUID().toString() }
    /** 当前详情页广告运行时策略；debugMode 仅用于调试广告源和测试广告位隔离。 */
    val runtimePolicy = remember(adsGlobalEnabled, disabledSourceIds, isMainProcess, isSensitiveContext) {
        AdRuntimePolicy(
            adsGlobalEnabled = adsGlobalEnabled,
            sessionDisabledSourceIds = disabledSourceIds,
            debugMode = com.cla.clip.master.BuildConfig.DEBUG,
            isMainProcess = isMainProcess,
            isSensitiveContext = isSensitiveContext,
        )
    }
    /** 当前广告同意状态；真实 SDK 必须显式 granted，调试源可使用 not_required。 */
    val adConsentState = remember(adConsentStateCode) {
        when (adConsentStateCode.trim().lowercase(Locale.ROOT)) {
            "granted" -> AdConsentState.Granted
            "denied" -> AdConsentState.Denied
            "not_required" -> AdConsentState.NotRequired
            else -> if (com.cla.clip.master.BuildConfig.DEBUG) AdConsentState.NotRequired else AdConsentState.Unknown
        }
    }
    /** 当前广告源选择结果；不可用时直接隐藏，不渲染空卡片。 */
    val selection = remember(adSources, activeAdSourceId, runtimePolicy, adConsentState) {
        adSourceSelector.select(
            sources = adSources,
            placement = AdPlacement.DetailNative,
            activeSourceId = activeAdSourceId,
            consentState = adConsentState,
            runtimePolicy = runtimePolicy,
        )
    }

    when (selection) {
        is AdSourceSelection.Hidden -> {
            LaunchedEffect(selection.reasonCode) {
                logD(DETAIL_AD_TAG) {
                    "详情页广告隐藏 placement=${AdPlacement.DetailNative.placementId} reasonCode=${selection.reasonCode}"
                }
            }
        }

        is AdSourceSelection.Selected -> {
            /** 当前广告请求；只携带广告位、请求标识和调试标记，不包含剪贴内容。 */
            val request = remember(requestNonce, runtimePolicy.debugMode) {
                AdSlotRequest(
                    placement = AdPlacement.DetailNative,
                    requestNonce = requestNonce,
                    isDebugRequest = runtimePolicy.debugMode,
                    isSensitiveContext = runtimePolicy.isSensitiveContext,
                )
            }

            selection.source.NativeAdSlot(
                request = request,
                onEvent = { event ->
                    // 初始化或渲染失败说明当前 source 本会话不再可信，触发保险丝避免反复失败。
                    if (event.eventType == AdSlotEventType.InitializationFailed || event.eventType == AdSlotEventType.RenderFailed) {
                        onDisableAdSource(event.providerId)
                    }
                    logDetailAdEvent(event)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 输出详情页广告低敏事件日志。
 *
 * 该日志只记录 source、placement、事件、reasonCode、耗时和调试标记，禁止记录剪贴正文、完整链接或 SDK 响应。
 */
private fun logDetailAdEvent(event: AdSlotEvent) {
    /** 事件耗时字段；未知时使用 -1，避免日志里出现 null 分支噪声。 */
    val duration = event.durationMs ?: -1L
    /** 事件原因码；空值用 none 表示正常路径。 */
    val reasonCode = event.reasonCode.ifBlank { "none" }
    /** SDK 版本号；空值用 unknown 表示当前源未提供版本。 */
    val sdkVersion = event.sdkVersion.ifBlank { "unknown" }
    /** 日志正文构造器；复用 lambda 避免日志关闭时拼接字符串。 */
    val message = {
        "详情页广告事件 providerId=${event.providerId} placement=${event.placementId} event=${event.eventType.eventCode} reasonCode=$reasonCode durationMs=$duration isDebug=${event.isDebug} sdkVersion=$sdkVersion"
    }
    if (event.eventType == AdSlotEventType.InitializationFailed || event.eventType == AdSlotEventType.RenderFailed) {
        logW(DETAIL_AD_TAG, info = message)
    } else {
        logD(DETAIL_AD_TAG, info = message)
    }
}

/**
 * 详情页操作分区。
 *
 * 当剪贴内容识别出链接时额外展示图片/视频提取入口；磁力搜索会用标题或正文作为初始关键词，不读取系统剪贴板。
 * 复制入口由正文卡片点击承载；删除入口保留在标题栏右侧低频操作位，降低正文底部干扰。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailActionSections(
    detailVm: DetailViewModel,
    onNavigate: (Route) -> Unit,
    magnetFeatures: Set<MagnetFeatureEntry>,
    onOpenMagnetSearch: (MagnetFeatureEntry, String) -> Unit,
    clip: ClipShowEntity,
    adSources: Set<AdSourceEntry>,
    adSourceSelector: AdSourceSelector,
    activeAdSourceIdFlow: StateFlow<String>,
    adsGlobalEnabledFlow: StateFlow<Boolean>,
    adConsentStateFlow: StateFlow<String>,
    adPrivacyPolicyVersionFlow: StateFlow<String>,
    adDisabledSourceIdsFlow: StateFlow<Set<String>>,
    isMainProcess: Boolean,
    detailAdSensitivityPolicy: DetailAdSensitivityPolicy,
    onDisableAdSource: (String) -> Unit,
) {
    /** 详情页操作区间距 token，确保正文、能力入口和普通操作区节奏一致。 */
    val spacing = ClipMasterThemeTokens.tokens.spacing
    /** 从正文实时提取出的链接展示模型；历史剪贴不需要迁移即可展示多链接。 */
    val linkItems = remember(clip.content) { buildDetailLinkItems(clip.content) }
    /** 当前详情页是否命中敏感内容保护；只传布尔值给广告策略，不记录正文或命中片段。 */
    val isSensitiveAdContext = remember(clip.id, clip.content) {
        detailAdSensitivityPolicy.shouldHideAds(clip.content)
    }
    /** 是否存在磁力扩展动作；没有动作时不渲染空操作行，避免移除复制按钮后留下无意义间距。 */
    val hasMagnetActions = magnetFeatures.isNotEmpty()
    /** 当前被用户选中并准备操作的链接；为 null 时不显示链接操作弹层。 */
    var selectedLink by remember { mutableStateOf<DetailLinkUiState?>(null) }
    /** 是否显示全部链接选择弹层；只在链接数量超过默认预览数量时使用。 */
    var showAllLinks by remember { mutableStateOf(false) }

    LaunchedEffect(clip.id) {
        // 剪贴记录切换时清空弹层状态，避免快速进入另一条详情后仍显示旧链接。
        selectedLink = null
        showAllLinks = false
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(spacing.small)
    ) {
        DetailAdSlot(
            clipId = clip.id,
            isSensitiveContext = isSensitiveAdContext,
            adSources = adSources,
            adSourceSelector = adSourceSelector,
            activeAdSourceIdFlow = activeAdSourceIdFlow,
            adsGlobalEnabledFlow = adsGlobalEnabledFlow,
            adConsentStateFlow = adConsentStateFlow,
            adPrivacyPolicyVersionFlow = adPrivacyPolicyVersionFlow,
            adDisabledSourceIdsFlow = adDisabledSourceIdsFlow,
            isMainProcess = isMainProcess,
            onDisableAdSource = onDisableAdSource,
        )

        if (linkItems.isNotEmpty()) {
            DetailLinksSection(
                links = linkItems,
                onCopyLink = { url ->
                    // 链接复制只写入当前链接，不刷新整条剪贴记录时间戳。
                    detailVm.copyToClipboard(url)
                },
                onOpenLinkActions = { link ->
                    /** 用户点选的链接；后续弹层只围绕该链接执行复制或提取动作。 */
                    selectedLink = link
                },
                onShowAllLinks = {
                    // 多链接超出默认展示数量时进入完整选择弹层，避免底部列表过高。
                    showAllLinks = true
                },
                onExtractVideo = { link ->
                    // 视频提取页需要原始页面 URL 和一个可读名称，名称用于后续生成下载任务文件名。
                    onNavigate(VideoExtractRoute(link.url, name = clip.linkTitle ?: clip.content))
                },
                onExtractImages = { link ->
                    // 图片提取已有独立页面，进入后会先提取并落库，再由用户确认批量下载。
                    onNavigate(ImageExtractRoute(link.url, name = clip.linkTitle ?: clip.content))
                }
            )
        }

        if (hasMagnetActions) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.small)
            ) {
                /** 磁力扩展动作的初始关键词，优先使用网页标题，缺失时回退到剪贴正文。 */
                val magnetInitialQuery = clip.linkTitle ?: clip.content
                magnetFeatures.sortedBy { it.featureId }.forEach { feature ->
                    with(feature) {
                        DetailAction(
                            initialQuery = magnetInitialQuery,
                            onOpenSearch = { query -> onOpenMagnetSearch(feature, query) }
                        )
                    }
                }
            }
        }
    }

    if (showAllLinks) {
        DetailAllLinksBottomSheet(
            links = linkItems,
            onDismiss = { showAllLinks = false },
            onSelectLink = { link ->
                /** 从全部链接弹层中选中的目标链接，会继续进入单链接操作弹层。 */
                selectedLink = link
                showAllLinks = false
            }
        )
    }

    selectedLink?.let { link ->
        DetailLinkActionBottomSheet(
            link = link,
            onDismiss = { selectedLink = null },
            onCopyLink = {
                // 弹层复制只复制完整 URL；摘要永远不参与剪贴板写入。
                detailVm.copyToClipboard(link.url)
                selectedLink = null
            },
            onExtractVideo = {
                // 进入视频提取前关闭弹层，避免导航后返回时残留旧操作面板。
                selectedLink = null
                onNavigate(VideoExtractRoute(link.url, name = clip.linkTitle ?: clip.content))
            },
            onExtractImages = {
                // 进入图片提取前关闭弹层，避免导航后返回时残留旧操作面板。
                selectedLink = null
                onNavigate(ImageExtractRoute(link.url, name = clip.linkTitle ?: clip.content))
            }
        )
    }
}

/**
 * 详情页链接区域。
 *
 * 单链接直接展示快捷操作；多链接只展示紧凑摘要，具体操作放到链接操作弹层里完成。
 */
@Composable
private fun DetailLinksSection(
    links: List<DetailLinkUiState>,
    onCopyLink: (String) -> Unit,
    onOpenLinkActions: (DetailLinkUiState) -> Unit,
    onShowAllLinks: () -> Unit,
    onExtractVideo: (DetailLinkUiState) -> Unit,
    onExtractImages: (DetailLinkUiState) -> Unit,
) {
    /** 详情页链接区间距 token，和页面其他操作区域保持一致。 */
    val spacing = ClipMasterThemeTokens.tokens.spacing
    /** 是否只有一条链接；单链接场景保留快速提取入口。 */
    val isSingleLink = links.size == 1

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(spacing.small)
    ) {
        Text(
            text = stringResource(R.string.base_general_recognized_link_count, links.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (isSingleLink) {
            /** 单链接场景下唯一的链接目标，快捷操作都围绕它执行。 */
            val link = links.first()
            DetailLinkSummaryRow(
                link = link,
                onClick = { onOpenLinkActions(link) }
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                modifier = Modifier.fillMaxWidth()
            ) {
                DetailLinkTextAction(
                    label = stringResource(R.string.base_general_copy_link),
                    onClick = { onCopyLink(link.url) }
                )
                if (link.canExtract) {
                    DetailLinkTextAction(
                        label = stringResource(R.string.base_general_video_extract),
                        onClick = { onExtractVideo(link) }
                    )
                    DetailLinkTextAction(
                        label = stringResource(R.string.base_general_image_extract),
                        onClick = { onExtractImages(link) }
                    )
                }
            }
            if (!link.canExtract) {
                Text(
                    text = stringResource(R.string.base_general_link_extract_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            /** 默认预览的前几条链接，剩余链接通过“查看全部链接”进入弹层。 */
            val visibleLinks = links.take(DETAIL_LINK_PREVIEW_LIMIT)
            visibleLinks.forEach { link ->
                DetailLinkSummaryRow(
                    link = link,
                    onClick = { onOpenLinkActions(link) }
                )
            }
            if (links.size > DETAIL_LINK_PREVIEW_LIMIT) {
                TextButton(onClick = onShowAllLinks) {
                    Text(stringResource(R.string.base_general_view_all_links))
                }
            }
        }
    }
}

/**
 * 详情页链接摘要行。
 *
 * 行只展示摘要和类型，点击后由调用方决定打开操作弹层或完整选择弹层。
 */
@Composable
private fun DetailLinkSummaryRow(
    link: DetailLinkUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = link.summary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = detailLinkTypeLabel(link.type),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 全部链接选择弹层。
 *
 * 只负责列出全部链接并把用户选择回传给详情页，避免在默认底部区域堆叠过多行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailAllLinksBottomSheet(
    links: List<DetailLinkUiState>,
    onDismiss: () -> Unit,
    onSelectLink: (DetailLinkUiState) -> Unit,
) {
    /** 链接选择弹层状态；跳过半展开，避免长链接列表在半高状态下难以浏览。 */
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.base_general_select_link),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
            LazyColumn {
                items(
                    items = links,
                    key = { link -> link.url }
                ) { link ->
                    DetailLinkSummaryRow(
                        link = link,
                        onClick = { onSelectLink(link) },
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }
        }
    }
}

/**
 * 单条链接操作弹层。
 *
 * 复制对所有识别到的链接开放；图片/视频提取只对公网 http/https 链接开放。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailLinkActionBottomSheet(
    link: DetailLinkUiState,
    onDismiss: () -> Unit,
    onCopyLink: () -> Unit,
    onExtractVideo: () -> Unit,
    onExtractImages: () -> Unit,
) {
    /** 链接操作弹层状态；跳过半展开，保证操作文案和完整链接提示一次可见。 */
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    /** 弹层内容滚动状态；完整链接很长时允许用户滚动查看所有内容。 */
    val sheetContentScrollState = rememberScrollState()
    /** 完整链接横向滚动状态；不截断 URL，复制和核对都以完整原文为准。 */
    val fullUrlScrollState = rememberScrollState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(sheetContentScrollState)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = detailLinkTypeLabel(link.type),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.base_general_full_link),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = link.url,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(fullUrlScrollState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                softWrap = false
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(ClipMasterThemeTokens.tokens.spacing.small),
                modifier = Modifier.fillMaxWidth()
            ) {
                DetailLinkTextAction(
                    label = stringResource(R.string.base_general_copy_link),
                    onClick = onCopyLink
                )
                if (link.canExtract) {
                    DetailLinkTextAction(
                        label = stringResource(R.string.base_general_video_extract),
                        onClick = onExtractVideo
                    )
                    DetailLinkTextAction(
                        label = stringResource(R.string.base_general_image_extract),
                        onClick = onExtractImages
                    )
                }
            }
            if (!link.canExtract) {
                Text(
                    text = stringResource(R.string.base_general_link_extract_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.size(4.dp))
        }
    }
}

/**
 * 详情页链接文字操作。
 *
 * 只使用主色文案和点击语义，不使用按钮外框，避免小屏下按钮最小宽度挤压文案换行。
 */
@Composable
private fun DetailLinkTextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        modifier = modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Clip
    )
}

/** 把链接类型映射为资源化文案，避免展示模型直接持有 Android 字符串。 */
@Composable
private fun detailLinkTypeLabel(type: DetailLinkType): String {
    return when (type) {
        DetailLinkType.Image -> stringResource(R.string.base_general_link_type_image)
        DetailLinkType.Media -> stringResource(R.string.base_general_link_type_media)
        DetailLinkType.Web -> stringResource(R.string.base_general_link_type_web)
        DetailLinkType.Other -> stringResource(R.string.base_general_link_type_other)
    }
}

/**
 * 从剪贴正文构建详情页链接展示模型。
 *
 * 该函数保持纯逻辑，便于单元测试覆盖多链接、摘要生成和提取安全边界。
 */
internal fun buildDetailLinkItems(content: String): List<DetailLinkUiState> {
    /** 按正文顺序提取出的完整链接，已经由 `LinkUtils` 完成尾部标点清理和完整 URL 去重。 */
    val urls = LinkUtils.extractUrls(content)
    /** 链接展示模型；摘要不追加序号，避免识别到的链接列表出现额外编号。 */
    val items = urls.map { url ->
        /** 链接类型，决定列表里的轻量提示。 */
        val type = resolveDetailLinkType(url)
        DetailLinkUiState(
            url = url,
            summary = summarizeDetailLink(url),
            type = type,
            canExtract = LinkUtils.isPublicHttpUrl(url)
        )
    }

    return items
}

/** 判断详情页链接类型，按更具体的图片/媒体直链优先归类。 */
private fun resolveDetailLinkType(url: String): DetailLinkType {
    return when {
        LinkUtils.isImageUrl(url) -> DetailLinkType.Image
        LinkUtils.isDownloadableMediaUrl(url) -> DetailLinkType.Media
        LinkUtils.isPublicHttpUrl(url) -> DetailLinkType.Web
        else -> DetailLinkType.Other
    }
}

/**
 * 生成链接摘要。
 *
 * 摘要只包含 host 和前几段 path，不包含 query；无法解析 host 时回退到原始 URL 截断。
 */
internal fun summarizeDetailLink(url: String): String {
    /** 尝试解析后的 URI；失败时说明 URL 不能可靠拆分 host/path。 */
    val uri = runCatching { URI(url) }.getOrNull()
    /** 摘要主机名；去掉 www 前缀以减少无效占宽。 */
    val host = uri?.host
        ?.lowercase(Locale.ROOT)
        ?.removePrefix("www.")
        ?.takeIf { it.isNotBlank() }
    if (host.isNullOrBlank()) {
        return shortenDetailLinkText(url, DETAIL_LINK_RAW_SUMMARY_MAX_LENGTH)
    }

    /** path 中可展示的前几段；query 永远不进入摘要。 */
    val pathSegments = uri.path
        .orEmpty()
        .split("/")
        .filter { it.isNotBlank() }
        .take(DETAIL_LINK_SUMMARY_PATH_SEGMENT_LIMIT)
        .map { segment -> shortenDetailLinkText(segment, DETAIL_LINK_PATH_SEGMENT_MAX_LENGTH) }

    if (pathSegments.isEmpty()) return host

    /** path 是否还有未展示内容，有剩余时用省略标记提示用户摘要被压缩。 */
    val hasMorePath = uri.path
        .orEmpty()
        .split("/")
        .filter { it.isNotBlank() }
        .size > DETAIL_LINK_SUMMARY_PATH_SEGMENT_LIMIT
    /** 摘要 path 文案，最多包含前两个 path 片段。 */
    val pathSummary = pathSegments.joinToString(separator = "/")
    return if (hasMorePath) {
        "$host/$pathSummary/..."
    } else {
        "$host/$pathSummary"
    }
}

/** 截断过长摘要片段，保证链接展示不会撑开详情页底部。 */
private fun shortenDetailLinkText(text: String, maxLength: Int): String {
    if (text.length <= maxLength) return text
    return text.take((maxLength - 3).coerceAtLeast(0)) + "..."
}
