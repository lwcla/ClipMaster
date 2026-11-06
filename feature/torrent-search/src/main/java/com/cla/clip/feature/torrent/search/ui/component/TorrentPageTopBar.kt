package com.cla.clip.feature.torrent.search.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cla.clip.feature.torrent.search.R

/** 功能内共享顶部栏：标题独占弹性行，动作换行，避免英文或动态字体被固定 AppBar 高度裁剪。 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun TorrentPageTopBar(
    /** 当前页面标题，允许完整多行显示。 */
    title: String,
    /** 返回操作由页面接线，不依赖 NavController。 */
    onBack: () -> Unit,
    /** 调用方外部布局约束。 */
    modifier: Modifier = Modifier,
    /** 可选页面动作独立换行；为空时不产生额外行或间距。 */
    actions: (@Composable () -> Unit)? = null,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.torrent_search_action_back))
                }
                Text(title, modifier = Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
            }
            if (actions != null) {
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.End, verticalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
            }
        }
    }
}
