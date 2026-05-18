package com.cla.clip.master.ui.page.video

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.cla.clip.base.general.R
import com.cla.clip.base.general.widget.RequestStoragePermission
import com.cla.clip.master.entity.VideoCandidate
import com.cla.clip.master.ui.theme.ClipMaterTheme
import com.cla.clip.master.ui.widget.InlineErrorState
import com.cla.clip.master.ui.widget.InlineLoadingState
import com.cla.clip.master.ui.widget.InlineSuccessState

/** 视频地址提取失败状态，点击整行会触发新一轮 session 重试。 */
@Composable
internal fun Filed(retry: () -> Unit) {
    InlineErrorState(
        text = stringResource(R.string.base_general_failed_to_extract_the_video_address),
        onClick = retry,
    )
}

@Preview(showBackground = true)
@Composable
private fun FailedPreview() {
    val context = LocalContext.current
    val retryText = stringResource(R.string.base_general_click_retry)
    ClipMaterTheme {
        Filed(
            retry = {
                Toast.makeText(context, retryText, Toast.LENGTH_SHORT).show()
            }
        )
    }
}

/** 视频地址探测中的前景提示。 */
@Composable
internal fun Loading() {
    InlineLoadingState(text = stringResource(R.string.base_general_extract_the_video_address))
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

        InlineSuccessState(text = stringResource(R.string.base_general_extract_the_video_address_success))

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
