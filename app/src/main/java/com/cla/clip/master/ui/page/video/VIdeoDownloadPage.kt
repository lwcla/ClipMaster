package com.cla.clip.master.ui.page.video

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cla.clip.base.general.R
import com.cla.clip.master.entity.VideoCandidate
import com.cla.clip.master.entity.VideoDownloadState
import com.cla.clip.master.ui.theme.ClipMaterTheme
import com.cla.clip.master.ui.widget.TitleBar

/** 视频下载页面 */
@Composable
fun VideoDownloadPage(
    downloadVm: VideoDownloadVm = hiltViewModel(),
    candidate: VideoCandidate,
    onBack: () -> Unit
) {
    val state = downloadVm.downloadState.collectAsStateWithLifecycle().value

    // 拦截系统返回键
    BackHandler {
        onBack()
    }

    LaunchedEffect(downloadVm.sessionId) {
        downloadVm.startDownload(downloadVm.sessionId, candidate)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TitleBar(title = stringResource(R.string.base_general_video_download), onBack = onBack)

        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            when (state) {
                is VideoDownloadState.Idle -> {
                    Text(
                        text = stringResource(R.string.base_general_preparing_download),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                is VideoDownloadState.Downloading -> {
                    Loading(state)
                }

                is VideoDownloadState.Success -> {
                    Success(state)
                }

                is VideoDownloadState.Failed -> {
                    Failed(
                        state,
                        retry = { downloadVm.sessionId++ /* 通过改变 sessionId 来触发重新下载 */ }
                    )
                }
            }
        }
    }
}

@Composable
private fun Failed(
    state: VideoDownloadState.Failed,
    retry: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = { retry() })
    ) {
        Icon(
            painter = rememberVectorPainter(Icons.Default.Error),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .padding(12.dp)
                .size(24.dp)
        )

        Text(
            text = stringResource(R.string.base_general_video_download_failed),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 0.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun FailedPreview() {
    ClipMaterTheme {
        Failed(
            state = VideoDownloadState.Failed(errorMsg = "下载失败，点击重试"),
            retry = {}
        )
    }
}

@Composable
private fun Success(state: VideoDownloadState.Success) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = rememberVectorPainter(Icons.Default.DownloadDone),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(12.dp)
                    .size(24.dp)
            )

            Text(
                text = stringResource(R.string.base_general_video_download_success),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 0.dp, top = 0.dp, end = 12.dp, bottom = 0.dp)
            )
        }

        Text(
            text = state.savePath ?: "",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .clickable(onClick = {

                })
                .padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 6.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SuccessPreview() {
    ClipMaterTheme {
        Success(VideoDownloadState.Success("download/path/video.mp4"))
    }
}

@Composable
private fun Loading(state: VideoDownloadState.Downloading) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 40.dp)
    ) {
        LinearProgressIndicator(
            progress = { state.progress.toFloat() / 100 },
            modifier = Modifier.weight(1f)
        )

        Text(
            text = "${state.progress}%",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LoadingPreview() {
    ClipMaterTheme {
        Loading(VideoDownloadState.Downloading(progress = 66))
    }
}