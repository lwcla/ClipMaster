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
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.cla.clip.master.ui.dialog.ClipDeleteChoiceDialog
import com.cla.clip.master.ui.navigation.ImageExtractRoute
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.navigation.VideoExtractRoute
import com.cla.clip.master.ui.widget.ClipMasterCard
import com.cla.clip.master.ui.widget.TitleBar
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
) {
    var deleteClip by remember { mutableStateOf<ClipShowEntity?>(null) }

    // 只在 clipId 变化时触发加载，避免每次重组都查库。
    LaunchedEffect(clipId) { detailVm.loadClip(clipId) }
    val uiState = detailVm.clipFlow.collectAsStateWithLifecycle().value

    LaunchedEffect(Unit) {
        detailVm.deleteSuccessFlow.collectLatest {
            // 删除成功后返回上一页，避免详情页继续展示已删除记录。
            onBack()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TitleBar(stringResource(R.string.base_general_clip_detail), onBack)

        when (uiState) {
            is DetailUiState.Loading -> {
                ClipMasterCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp, top = 0.dp, end = 12.dp, bottom = 12.dp)
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
                        .padding(12.dp, top = 0.dp, end = 12.dp, bottom = 12.dp)
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                    .padding(12.dp, top = 0.dp, end = 12.dp, bottom = 12.dp)
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

                ButtonContainer(
                    detailVm = detailVm,
                    onNavigate = onNavigate,
                    onDelete = { clip -> deleteClip = clip },
                    clip = clip
                )
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
 * 详情页底部操作区。
 *
 * 当剪贴内容识别出链接时额外展示图片/视频提取入口；删除和复制始终可用，保证普通文本记录也能操作。
 */
@Composable
private fun ButtonContainer(
    detailVm: DetailViewModel,
    onNavigate: (Route) -> Unit,
    onDelete: (ClipShowEntity) -> Unit,
    clip: ClipShowEntity
) {
    Column(
        modifier = Modifier.padding(start = 12.dp, top = 0.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
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
                style = MaterialTheme.typography.bodySmall
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        // 视频提取页需要原始页面 URL 和一个可读名称，名称用于后续生成下载任务文件名。
                        onNavigate(VideoExtractRoute(link, name = clip.linkTitle ?: clip.content))
                    }
                ) {
                    Text(stringResource(R.string.base_general_video_extract))
                }

                Button(
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
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = { onDelete(clip) }
            ) {
                Text(stringResource(R.string.base_general_delete))
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
