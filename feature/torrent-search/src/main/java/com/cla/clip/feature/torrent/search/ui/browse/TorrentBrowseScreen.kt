package com.cla.clip.feature.torrent.search.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cla.clip.feature.torrent.search.R
import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.ui.component.TorrentResultCard
import com.cla.clip.feature.torrent.search.ui.component.toTorrentResultCardState
import com.cla.clip.feature.torrent.search.ui.component.torrentCategoryLabel

/** 原生 Latest/Top 浏览页，只消费稳定 UI state 并上抛事件。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TorrentBrowseScreen(
    /** 浏览页完整不可变状态。 */
    state: TorrentBrowseUiState,
    /** 切换 Latest/Top 的页面事件。 */
    onModeChange: (BrowseMode) -> Unit,
    /** 切换分类的页面事件。 */
    onCategoryChange: (Category) -> Unit,
    /** 当前条件显式重试事件。 */
    onRetry: () -> Unit,
    /** 结果点击交给导航根保存内存候选。 */
    onResultClick: (Torrent) -> Unit,
    /** 返回搜索页事件。 */
    onBack: () -> Unit,
    /** 页面外部布局约束。 */
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.torrent_search_browse_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.torrent_search_action_back),
                        )
                    }
                },
            )
        },
    ) { /** paddingValues 是 Scaffold 为系统栏与顶部栏提供的安全内边距。 */ paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
        ) {
            BrowseControls(
                state = state,
                onModeChange = onModeChange,
                onCategoryChange = onCategoryChange,
            )
            if (state.isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            BrowseContent(
                state = state,
                onRetry = onRetry,
                onResultClick = onResultClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 展示模式与分类筛选，安全模式下不生成成人分类控件。 */
@Composable
private fun BrowseControls(
    /** 控件需要的当前选择和安全模式。 */
    state: TorrentBrowseUiState,
    /** 模式选择事件。 */
    onModeChange: (BrowseMode) -> Unit,
    /** 分类选择事件。 */
    onCategoryChange: (Category) -> Unit,
) {
    /** visibleCategories 是安全模式过滤后的稳定分类集合。 */
    val visibleCategories = Category.entries.filter { /** category 是待判定可见性的稳定分类。 */ category ->
        !state.safeModeEnabled || !category.isNsfw
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = BrowseMode.entries, key = { /** mode 是浏览模式稳定 key。 */ mode -> mode.name }) { /** mode 是当前生成选择控件的浏览模式。 */ mode ->
                FilterChip(
                    selected = state.mode == mode,
                    onClick = { onModeChange(mode) },
                    label = {
                        Text(
                            stringResource(
                                if (mode == BrowseMode.LATEST) R.string.torrent_search_browse_sort_latest
                                else R.string.torrent_search_browse_sort_top,
                            ),
                        )
                    },
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = visibleCategories, key = { /** category 是分类稳定 key。 */ category -> category.code }) { /** category 是当前生成选择控件的安全分类。 */ category ->
                FilterChip(
                    selected = state.category == category,
                    onClick = { onCategoryChange(category) },
                    label = { Text(torrentCategoryLabel(category)) },
                )
            }
        }
    }
}

/** 按页面阶段展示结果、加载占位或可重试空态。 */
@Composable
private fun BrowseContent(
    /** 浏览结果与阶段快照。 */
    state: TorrentBrowseUiState,
    /** 空态重试事件。 */
    onRetry: () -> Unit,
    /** 结果点击事件。 */
    onResultClick: (Torrent) -> Unit,
    /** 内容区域布局约束。 */
    modifier: Modifier = Modifier,
) {
    if (state.phase == BrowsePhase.COMPLETED && state.results.isEmpty()) {
        Column(
            modifier = modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Text(
                text = stringResource(R.string.torrent_search_browse_state_unavailable_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.torrent_search_browse_state_unavailable_description),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onRetry) {
                Text(stringResource(R.string.torrent_search_browse_button_try_again))
            }
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.results.isNotEmpty()) {
            item(key = "browse_result_count") {
                Text(
                    text = stringResource(R.string.torrent_search_browse_torrents_count_format, state.results.size),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        items(items = state.results, key = { /** torrent 是稳定 key 来源。 */ torrent -> torrent.infoHash }) { /** torrent 是当前浏览结果。 */ torrent ->
            TorrentResultCard(
                state = torrent.toTorrentResultCardState(),
                onClick = { onResultClick(torrent) },
            )
        }
    }
}
