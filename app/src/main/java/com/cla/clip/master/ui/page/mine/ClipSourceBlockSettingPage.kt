package com.cla.clip.master.ui.page.mine

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cla.clip.base.general.R
import com.cla.clip.master.ui.theme.ClipMasterThemeTokens
import com.cla.clip.master.ui.widget.SearchInputField
import com.cla.clip.master.ui.widget.SecondaryPageScaffold
import com.cla.clip.master.ui.widget.SelectableAppRow
import com.cla.clip.master.ui.widget.SelectableAppRowInteraction
import com.cla.clip.master.ui.widget.SelectableAppRowState
import com.cla.clip.master.ui.widget.ToolbarSwitchAction

/** 来源过滤页只连接已提交状态与单项操作；搜索、显隐和刷新不裁剪隐藏规则。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipSourceBlockSettingPage(
    viewModel: ClipSourceBlockSettingVm = hiltViewModel(),
    onBack: () -> Unit,
) {
    /** 首次有效设置快照到达前禁用提交，完整集合包含未安装及隐藏应用。 */
    val blockedPackages by viewModel.blockedClipSourcePackages.collectAsStateWithLifecycle()
    /** 设置读取失败独立于安装扫描失败，失败快照不能用于修改名单。 */
    val settingsLoadFailed by viewModel.settingsLoadFailed.collectAsStateWithLifecycle()
    /** 候选按已提交名单分组，保持已过滤优先及同组原扫描顺序。 */
    val candidates by viewModel.blockedSourceAppCandidates.collectAsStateWithLifecycle()
    /** 会话内安装范围、搜索和刷新状态，不进入设置或备份。 */
    val installedState by viewModel.installedAppListState.collectAsStateWithLifecycle()
    /** 行级提交与返回等待由独立协调器管理，扫描取消不影响已接受保存。 */
    val writeState by viewModel.writeState.collectAsStateWithLifecycle()
    /** 展示过滤复用原有纯规则，不从可见列表重建持久名单。 */
    val visibleCandidates = remember(candidates, installedState.searchQuery, installedState.showSystemApps) {
        filterClipSourceBlockCandidates(candidates, installedState.searchQuery, installedState.showSystemApps)
    }
    /** 导航回调保持最新，等待提交的协程无需捕获过期页面回调。 */
    val currentOnBack by rememberUpdatedState(onBack)
    /** 搜索动作只收起键盘，不触发扫描或持久化。 */
    val keyboard = LocalSoftwareKeyboardController.current
    /** 标题返回和系统返回共用同一等待边界，全部提交成功后才自动离页。 */
    val requestBack: () -> Unit = {
        if (viewModel.requestBack()) currentOnBack()
    }

    // 页面前台才启动扫描；暂停或离页只取消扫描任务，已接受写入由 ViewModel 继续完成。
    LifecycleResumeEffect(viewModel) {
        viewModel.onVisible()
        onPauseOrDispose { viewModel.onHidden() }
    }
    // 系统返回不绕过仍在提交的单项事务。
    BackHandler(onBack = requestBack)
    // 返回等待状态只在所有写入成功后发出导航；失败撤销自动返回并留在页面提示。
    LaunchedEffect(writeState.navigateBack) {
        if (writeState.navigateBack) currentOnBack()
    }

    SecondaryPageScaffold(
        title = stringResource(R.string.base_general_clip_source_block_setting),
        onBack = requestBack,
        actions = {
            ToolbarSwitchAction(
                label = stringResource(com.cla.clip.master.R.string.host_clip_source_filter_system_apps),
                accessibilityDescription = stringResource(R.string.base_general_clip_source_block_show_system_apps),
                checked = installedState.showSystemApps,
                onCheckedChange = viewModel::updateShowSystemApps,
            )
        },
    ) { paddingValues ->
        // paddingValues 来自二级页面骨架，系统导航栏避让由骨架统一处理。
        Column(
            Modifier.fillMaxSize().padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(ClipMasterThemeTokens.tokens.spacing.small),
        ) {
            if (settingsLoadFailed) {
                TextButton(onClick = viewModel::retrySettingsLoad) {
                    Text(stringResource(R.string.base_general_data_load_failed_retry))
                }
            } else if (blockedPackages == null) {
                Text(stringResource(R.string.base_general_loading), modifier = Modifier.padding(horizontal = 20.dp))
            }
            Text(
                text = clipSourceBlockHeaderSubtitle(blockedPackages.orEmpty().size, installedState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            SearchInputField(
                query = installedState.searchQuery,
                onQueryChange = viewModel::updateSearchQuery,
                // 本页无搜索历史和焦点驱动任务，不需要存储焦点状态。
                onFocusChange = {},
                onSubmit = { keyboard?.hide() },
                placeholder = stringResource(R.string.base_general_clip_source_block_search_hint),
                clearContentDescription = stringResource(R.string.base_general_clear_search_keyword),
            )
            if (installedState.unavailableReasonCode != null) {
                Text(
                    stringResource(R.string.base_general_clip_source_block_local_read_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            if (writeState.backRequested) {
                Text(
                    stringResource(com.cla.clip.master.R.string.host_clip_source_filter_waiting_save),
                    modifier = Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            PullToRefreshBox(
                isRefreshing = installedState.loading,
                onRefresh = viewModel::refreshInstalledApps,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                // 始终保留可滚动容器，空结果和不足一屏也能下拉；包名稳定 key 保持分组后的行身份。
                LazyColumn(Modifier.fillMaxSize()) {
                    items(visibleCandidates, key = { candidate -> candidate.packageName }) { candidate ->
                        // candidate 只适配共享行；优先展示当前行提交状态，其余行可独立操作。
                        SelectableAppRow(
                            state = SelectableAppRowState(
                                title = candidate.appName,
                                subtitle = candidate.packageName,
                                icon = candidate.icon,
                                selected = candidate.packageName in blockedPackages.orEmpty(),
                                interaction = when {
                                    candidate.packageName in writeState.pendingPackages -> SelectableAppRowInteraction.Submitting
                                    blockedPackages == null || settingsLoadFailed || writeState.backRequested -> SelectableAppRowInteraction.Disabled
                                    else -> SelectableAppRowInteraction.Available
                                },
                            ),
                            // selected 是明确目标，直接交给仓库增删，不创建页面草稿或二次确认。
                            onSelectedChange = { selected -> viewModel.setSourceBlocked(candidate.packageName, selected) },
                        )
                    }
                    if (visibleCandidates.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                stringResource(R.string.base_general_clip_source_block_empty_candidates),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 页面专属摘要只统计已提交名单，刷新时保留上次成功数量，首次扫描显示读取状态。 */
@Composable
private fun clipSourceBlockHeaderSubtitle(selectedCount: Int, state: InstalledAppListState): String {
    /** 最近成功摘要独立于刷新失败状态，不用于推断当前可见列表或持久化。 */
    val summary = state.lastLoadSummary
    return when {
        summary != null -> stringResource(R.string.base_general_clip_source_block_local_load_summary, selectedCount, summary.appCount)
        state.loading -> stringResource(R.string.base_general_clip_source_block_loading_local_apps)
        state.unavailableReasonCode != null -> stringResource(R.string.base_general_clip_source_block_local_load_failed, selectedCount)
        else -> stringResource(R.string.base_general_clip_source_block_never_loaded, selectedCount)
    }
}