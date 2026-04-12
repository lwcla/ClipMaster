package com.cla.clip.master.ui.page.video

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cla.clip.base.general.R
import com.cla.clip.base.general.utils.toast
import com.cla.clip.master.entity.VideoDownloadState
import com.cla.clip.master.ui.theme.ClipMaterTheme
import com.cla.clip.master.ui.widget.TitleBar
import kotlinx.coroutines.launch

/** 视频下载页面 */
@Composable
fun VideoDownloadPage(
    downloadVm: VideoDownloadVm = hiltViewModel(),
    taskId: Long,
    onBack: () -> Unit
) {
    val state = downloadVm.downloadState.collectAsStateWithLifecycle().value

    // 拦截系统返回键
    BackHandler {
        onBack()
    }

    LaunchedEffect(downloadVm.sessionId) {
        downloadVm.startDownload(downloadVm.sessionId, taskId)
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
                        retry = {
                            // 通过改变 sessionId 来触发重新下载
                            downloadVm.sessionId++
                        }
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.clickable(onClick = {
            val uri = state.savePath?.let(Uri::parse) ?: return@clickable
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(intent)
            }.onFailure {
                scope.launch { context.toast(R.string.base_general_there_is_no_available_application_to_open_this_video) }
            }
        })
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