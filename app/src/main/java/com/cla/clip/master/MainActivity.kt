package com.cla.clip.master

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.cla.clip.base.general.utils.ApplicationScope
import com.cla.clip.base.general.widget.RequestStoragePermission
import com.cla.clip.master.ui.navigation.AppNavigation
import com.cla.clip.master.ui.page.video.VideoCandidate
import com.cla.clip.master.ui.page.video.VideoExtractVm
import com.cla.clip.master.ui.theme.ClipMaterTheme
import com.cla.clip.master.ui.widget.ShizukuServiceUnavailableTip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val videoExtractVm by viewModels<VideoExtractVm>()

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ClipMaterTheme {
                val navController = rememberNavController()
                var pendingCandidate by remember { mutableStateOf<Pair<Long, VideoCandidate>?>(null) }


                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                ) { innerPadding ->


                    Column(modifier = Modifier.padding(innerPadding)) {

                        pendingCandidate?.let { pending ->
                            key(pending.first) {
                                RequestStoragePermission(
                                    next = {
                                        pendingCandidate = null
//                                        probeState = ProbeState.Download(DownloadResult(0, isFailed = false, isComplete = false))
                                        // todo 开始下载视频
                                        handleDownload(pending.second)
                                    }
                                )
                            }
                        }


                        Button(onClick = {
                           val candidate= VideoCandidate(
                                url = "https://www.iesdouyin.com/aweme/v1/playwm/?line=0&logo_name=aweme_diversion_search&ratio=720p&video_id=v0d00fg10000d75kkb7og65p8l3jj7s0",
                                referer = "https://www.iesdouyin.com/share/video/7623268660349553955/?region=CN&mid=7376184036991993892&u_code=19ef6g9kd&did=MS4wLjABAAAAauVK2_NoF_ylq5gr6zl-_mozjrkiCyiOx7dpk-0BKIyEM4vBl2agGhMrbDUXXIZA&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ&with_sec_did=1&video_share_track_ver=&titleType=title&share_sign=25N_YTcoHEvoGqqJF0zpNCAb7U2pIREtvdU8Kvgbo2s-&share_version=170400&ts=1775367063&from_aid=6383&from_ssr=1&share_track_info=%7B%22link_description_type%22%3A%22%22%7D&from=web_code_link",
                                userAgent = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36",
                                cookie = "ttwid=1%7CLNMp7S_vPfmt1K5fk0cSv-jILK1PkofQymrfIAHCfWY%7C1775547586%7C2b6e1c2d75f4db5d4d1fac2fb69ff439fc5251ec14ee9242b49e09941e20ca47; gfkadpd=1243,16720; x-web-secsdk-uid=9160984e-2839-43ca-be40-849b1a97bedc"
                            )

                            pendingCandidate = System.currentTimeMillis() to candidate
//                            handleDownload(candidate)
                        }
                        ) {
                            Text("去下载")
                        }

                        ShizukuServiceUnavailableTip()
                        AppNavigation(navController)
                    }
                }
            }
        }
    }

    // 点击下载时
    fun handleDownload(candidate: VideoCandidate) {
        // 启动下载并得到 Flow
        val taskFlow = videoExtractVm.startDownload(
            videoUrl = candidate.url,
            referer = candidate.referer,
            userAgent = candidate.userAgent,
            cookie = candidate.cookie
        )

        // 监听状态变化
        scope.launch {
            taskFlow.collectLatest { task ->
//                downloadTaskState = task
//                if (task != null) {
//                    when (task.status) {
//                        "downloading" -> probeState = ProbeState.Download(
//                            DownloadResult(task.progress, false, false)
//                        )
//
//                        "success" -> probeState = ProbeState.Download(
//                            DownloadResult(100, false, true)
//                        )
//
//                        "failed" -> probeState = ProbeState.Download(
//                            DownloadResult(0, true, false)
//                        )
//                    }
//                }
            }
        }
    }
}


