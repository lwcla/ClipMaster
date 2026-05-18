package com.cla.clip.master.ui.page.video

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.cla.clip.base.general.R
import com.cla.clip.base.general.widget.RequestStoragePermission
import com.cla.clip.master.entity.VideoCandidate
import com.cla.clip.master.ui.theme.ClipMaterTheme

/** 视频地址提取失败状态，点击整行会触发新一轮 session 重试。 */
@Composable
internal fun Filed(retry: () -> Unit) {
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
            stringResource(R.string.base_general_failed_to_extract_the_video_address),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 0.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun FailedPreview() {
    val context = LocalContext.current
    ClipMaterTheme {
        Filed(
            retry = {
                Toast.makeText(context, "点击重试", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

/** 视频地址探测中的前景提示。 */
@Composable
internal fun Loading() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(25.dp),
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = stringResource(R.string.base_general_extract_the_video_address),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LoadingPreview() {
    ClipMaterTheme {
        Loading()
    }
}

/**
 * 视频地址识别成功状态。
 *
 * 点击下载前先走存储权限申请；pendingCandidate 额外带时间戳，是为了同一个候选地址重复点击也能重新触发权限组件。
 */
@Composable
internal fun Success(
    videoExtractVm: VideoExtractVm,
    candidate: VideoCandidate,
) {
    var pendingCandidate by remember { mutableStateOf<Pair<Long, VideoCandidate>?>(null) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        pendingCandidate?.let { pending ->
            key(pending.first) {
                RequestStoragePermission(
                    next = {
                        pendingCandidate = null
                        // 权限确认后再创建下载任务，避免 Worker 启动后才发现没有保存权限。
                        videoExtractVm.startDownloadAndGo(pending.second)
                    }
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = rememberVectorPainter(Icons.Default.Done),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(12.dp)
                    .size(24.dp)
            )
            Text(
                stringResource(R.string.base_general_extract_the_video_address_success),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 0.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
            )
        }

        Button(
            onClick = { pendingCandidate = System.currentTimeMillis() to candidate }
        ) {
            Text(text = stringResource(R.string.base_general_to_download))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SuccessPreview() {
    ClipMaterTheme {
        Success(
            videoExtractVm = hiltViewModel(),
            candidate = VideoCandidate(
                "https://example.com/video.mp4",
                "https://example.com",
                "Mozilla/5.0",
                "cookie=value",
                ""
            ),
        )
    }
}
