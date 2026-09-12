package com.cla.clip.feature.torrent.search

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cla.clip.feature.torrent.search.input.TorrentSearchIntentPayload
import com.cla.clip.feature.torrent.search.input.TorrentSearchIntentResolver
import com.cla.clip.feature.torrent.search.input.toTorrentSearchIntentPayload
import dagger.hilt.android.AndroidEntryPoint

/**
 * TorrentSearch 独立 Activity 空壳。
 *
 * 当前验证模块边界、Hilt 和系统入口输入消费；搜索页面与生命周期网络任务留给后续任务实现。
 */
@AndroidEntryPoint
class TorrentSearchActivity : ComponentActivity() {
    /** 最近一次启动请求；Activity 重用时由 onNewIntent 原子替换，供后续页面状态入口消费。 */
    private var launchRequest by mutableStateOf(
        TorrentSearchIntentResolver.resolve(TorrentSearchIntentPayload()),
    )

    /** 解析初始 Intent 并创建独立 Compose 容器；本阶段只预填，不发起网络或写入历史。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeIntent(intent)
        setContent {
            /** visibleQuery 只在存在安全预填文本时替换占位说明，让当前接入结果可人工观察。 */
            val visibleQuery = launchRequest.query.takeIf { query -> query.isNotBlank() }
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.torrent_search_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = visibleQuery ?: stringResource(R.string.torrent_search_placeholder_message),
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
