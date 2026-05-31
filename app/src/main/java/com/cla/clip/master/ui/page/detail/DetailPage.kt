package com.cla.clip.master.ui.page.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cla.clip.base.general.R
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.feature.magnet.api.MagnetFeatureEntry
import com.cla.clip.master.ui.dialog.ClipDeleteChoiceDialog
import com.cla.clip.master.ui.navigation.ImageExtractRoute
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.navigation.VideoExtractRoute
import com.cla.clip.master.ui.widget.ClipMasterCard
import com.cla.clip.master.ui.widget.SecondaryPageScaffold
import com.cla.clip.master.ui.theme.ClipMasterThemeTokens
import kotlinx.coroutines.flow.collectLatest

/**
 * 剪贴详情页。
 *
 * 页面根据路由传入的 `clipId` 加载单条剪贴记录，提供删除、复制以及跳转到图片/视频提取的入口。
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
                IconButton(
                    onClick = {
                        // 标题栏删除入口只打开现有二次确认弹窗，不直接执行危险操作。
                        deleteClip = clip
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = stringResource(R.string.base_general_delete)
                    )
                }
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
                        // 正文可能非常长，Card 内部滚动可以保留顶部标题和底部操作按钮的稳定位置。
                        ClipMasterCard(
                            modifier = Modifier.fillMaxWidth()
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
                        clip = clip
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
 * 详情页操作分区。
 *
 * 当剪贴内容识别出链接时额外展示图片/视频提取入口；磁力搜索会用标题或正文作为初始关键词，不读取系统剪贴板。
 * 复制作为普通主操作保留；删除入口上移到标题栏右侧低频操作位，降低正文底部干扰。
 */
@Composable
private fun DetailActionSections(
    detailVm: DetailViewModel,
    onNavigate: (Route) -> Unit,
    magnetFeatures: Set<MagnetFeatureEntry>,
    onOpenMagnetSearch: (MagnetFeatureEntry, String) -> Unit,
    clip: ClipShowEntity
) {
    /** 详情页操作区间距 token，确保正文、能力入口和普通操作区节奏一致。 */
    val spacing = ClipMasterThemeTokens.tokens.spacing
    Column(
        verticalArrangement = Arrangement.spacedBy(spacing.small)
    ) {
        val link = clip.link
        if (link.isNullOrBlank().not()) {
            val tipText = buildAnnotatedString {
                append(stringResource(R.string.base_general_videos_or_pictures_from_web_pages_can_be_extracted_1))
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "LINK",
                        styles = TextLinkStyles(
                            style = SpanStyle(color = MaterialTheme.colorScheme.error)
                        )
                    ) {
                        // 只点击链接片段时触发复制，避免整段说明文字都变成可点击区域。
                        detailVm.copyToClipboard(link)
                    }
                ) {
                    append(link)
                }

                append(stringResource(R.string.base_general_videos_or_pictures_from_web_pages_can_be_extracted_2))
            }
            Text(
                text = tipText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.small)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        // 视频提取页需要原始页面 URL 和一个可读名称，名称用于后续生成下载任务文件名。
                        onNavigate(VideoExtractRoute(link, name = clip.linkTitle ?: clip.content))
                    }
                ) {
                    Text(stringResource(R.string.base_general_video_extract))
                }

                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        // 图片提取已有独立页面，进入后会先提取并落库，再由用户确认批量下载。
                        onNavigate(ImageExtractRoute(link, name = clip.linkTitle ?: clip.content))
                    }
                ) {
                    Text(stringResource(R.string.base_general_image_extract))
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.small)
        ) {
            val magnetInitialQuery = clip.linkTitle ?: clip.content
            magnetFeatures.sortedBy { it.featureId }.forEach { feature ->
                with(feature) {
                    DetailAction(
                        initialQuery = magnetInitialQuery,
                        onOpenSearch = { query -> onOpenMagnetSearch(feature, query) }
                    )
                }
            }

            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    detailVm.copyToClipboard(clip)
                }
            ) {
                Text(stringResource(R.string.base_general_copy))
            }
        }
    }
}
