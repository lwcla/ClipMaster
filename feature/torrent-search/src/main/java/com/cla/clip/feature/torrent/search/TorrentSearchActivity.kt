package com.cla.clip.feature.torrent.search

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.cla.clip.feature.torrent.search.input.TorrentSearchIntentPayload
import com.cla.clip.feature.torrent.search.input.TorrentSearchIntentResolver
import com.cla.clip.feature.torrent.search.input.toTorrentSearchIntentPayload
import com.cla.clip.feature.torrent.search.ui.TorrentSearchApp
import dagger.hilt.android.AndroidEntryPoint

/**
 * TorrentSearch 独立 Activity。
 *
 * 只负责系统入口、主题和 feature-local 导航装配；页面状态与网络生命周期交给 Hilt ViewModel。
 */
@AndroidEntryPoint
class TorrentSearchActivity : ComponentActivity() {
    /** 最近一次启动请求；Activity 重用时由 onNewIntent 原子替换，供后续页面状态入口消费。 */
    private var launchRequest by mutableStateOf(
        TorrentSearchIntentResolver.resolve(TorrentSearchIntentPayload()),
    )

    /** 解析初始 Intent 并创建独立 Compose 导航容器；Intent 只预填，不自动联网。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeIntent(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TorrentSearchApp(
                        launchRequest = launchRequest,
                        onFinish = ::finish,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    /** singleTop Activity 收到新入口时更新系统持有的 Intent 和页面启动快照。 */
    override fun onNewIntent(
        /** 新到达的显式或系统 Intent；所有 extra 都按不可信文本处理。 */
        intent: android.content.Intent,
    ) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    /** 把 Android Intent 薄映射为纯输入，再复用统一 resolver 更新可观察状态。 */
    private fun consumeIntent(
        /** 当前需要消费的 Activity Intent。 */
        incomingIntent: android.content.Intent,
    ) {
        launchRequest = TorrentSearchIntentResolver.resolve(incomingIntent.toTorrentSearchIntentPayload())
    }
}
