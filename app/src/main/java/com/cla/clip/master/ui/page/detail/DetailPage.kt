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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.cla.clip.master.ui.dialog.DeleteDialog
import com.cla.clip.master.ui.navigation.ImageExtractRoute
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.navigation.VideoExtractRoute
import com.cla.clip.master.ui.widget.TitleBar
import kotlinx.coroutines.flow.collectLatest

/** 详情页 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailPage(
    detailVm: DetailViewModel = hiltViewModel(),
    clipId: Long,
    onBack: () -> Unit,
    onNavigate: (Route) -> Unit,  // 跳转页面
) {
    var deleteClip by remember { mutableStateOf<ClipShowEntity?>(null) }

    // 1) 只在 clipId 变化时触发加载，避免每次重组都查库
    LaunchedEffect(clipId) { detailVm.loadClip(clipId) }
    // 2) 订阅 flow
    val uiState = detailVm.clipFlow.collectAsStateWithLifecycle().value

    LaunchedEffect(Unit) {
        detailVm.deleteSuccessFlow.collectLatest {
            onBack() // 删除成功后返回上一页
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TitleBar(stringResource(R.string.base_general_clip_detail), onBack)

        when (uiState) {
            is DetailUiState.Loading -> {
                Card(
                    shape = RoundedCornerShape(8.dp),
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
                Card(
                    shape = RoundedCornerShape(8.dp),
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
                    // 这里包一层card是为了做圆角效果
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = clip.content,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
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

    DeleteDialog(
        clip = deleteClip,
        onDismiss = { deleteClip = null },
        onConfirmDelete = { clip ->
            detailVm.deleteClip(clip, sendEvent = true)
        }
    )
}

/** 详情页底部的按钮的容器 */
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
                        // 只点击 link 这段时触发
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
                        // https://v.douyin.com/bzLHPnkAbhs/ 这个链接是抖音的一个视频链接，测试用的，实际使用时应该是 clip.link
                        // https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8
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
