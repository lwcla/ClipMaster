package com.cla.clip.master.ui.page.mine

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import com.cla.clip.base.general.R
import com.cla.clip.master.ui.theme.ClipMasterThemeTokens
import com.cla.clip.master.ui.widget.CenteredStateContent
import com.cla.clip.master.ui.widget.InlineLoadingState
import com.cla.clip.master.ui.widget.SearchTopBar
import com.cla.clip.master.ui.widget.SecondaryPageScaffold
import com.cla.clip.master.ui.widget.SelectableAppRow
import com.cla.clip.master.ui.widget.sourceapp.AppIcon
import com.cla.clip.master.ui.widget.sourceapp.rememberAppIconLoader
import com.cla.clip.master.ui.widget.SelectableAppRowInteraction
import com.cla.clip.master.ui.widget.SelectableAppRowState
import com.cla.clip.master.ui.widget.ToggleFilterChip
import com.cla.clip.master.ui.widget.TitleBar
import kotlinx.coroutines.flow.first

/** 模式以 Boolean 保存，避免把枚举名称当成 SavedState 协议；关键词仍只驻留 ViewModel。 */
private val ClipSourceFilterSearchModeSaver = Saver<ClipSourceFilterSearchMode, Boolean>(
    // mode 表示当前顶部形态，保存不包含任何用户输入或安装数据。
    save = { mode -> mode == ClipSourceFilterSearchMode.Searching },
    // searching 只恢复展示形态，进程重建后的关键词按现有内存契约为空。
    restore = { searching -> if (searching) ClipSourceFilterSearchMode.Searching else ClipSourceFilterSearchMode.Browsing },
)

/** 来源过滤页连接已提交状态、单项操作与内容状态；成功快照就绪前不宣告空结果。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ClipSourceBlockSettingPage(
    viewModel: ClipSourceBlockSettingVm = hiltViewModel(),
    onBack: () -> Unit,
) {
    /** 安装候选不携带图像，当前可见行按包名加载。 */
    val iconLoader = rememberAppIconLoader()
    /** 首次有效设置快照到达前禁用提交，完整集合包含未安装及隐藏应用。 */
    val blockedPackages by viewModel.blockedClipSourcePackages.collectAsStateWithLifecycle()
    /** 设置读取失败独立于安装扫描失败，失败快照不能用于修改名单。 */
    val settingsLoadFailed by viewModel.settingsLoadFailed.collectAsStateWithLifecycle()
    /** 候选与来源扫描摘要一起观察，映射尚未追上成功状态时不能判空。 */
    val candidateSnapshot by viewModel.blockedSourceAppCandidateSnapshot.collectAsStateWithLifecycle()
    /** 候选按已提交名单分组，保持已过滤优先及同组原扫描顺序。 */
    val candidates = candidateSnapshot.candidates
    /** 会话内安装范围、搜索和刷新状态，不进入设置或备份。 */
    val installedState by viewModel.installedAppListState.collectAsStateWithLifecycle()
    /** 行级提交与返回等待由独立协调器管理，扫描取消不影响已接受保存。 */
    val writeState by viewModel.writeState.collectAsStateWithLifecycle()
    /** 展示过滤复用原有纯规则，不从可见列表重建持久名单。 */
    val visibleCandidates = remember(candidates, installedState.searchQuery, installedState.showSystemApps) {
        filterClipSourceBlockCandidates(candidates, installedState.searchQuery, installedState.showSystemApps)
    }
    /** 纯内容归约区分首帧、读取、失败和成功空态，异步候选尚未就绪时继续显示加载。 */
    val listContentState = resolveClipSourceBlockListContentState(
        installedState, candidates.size, visibleCandidates.size, candidateSnapshot.loadSummary,
    )
    /** 导航回调保持最新，等待提交的协程无需捕获过期页面回调。 */
    val currentOnBack by rememberUpdatedState(onBack)
    /** 顶部模式可恢复配置变化，不复制协调器中的关键词或候选状态。 */
    var searchMode by rememberSaveable(stateSaver = ClipSourceFilterSearchModeSaver) {
        mutableStateOf(ClipSourceFilterSearchMode.Browsing)
    }
    /** 输入请求绑定当前 Compose 焦点树，不能放进 ViewModel 或持久化。 */
    val searchFocusRequester = remember { FocusRequester() }
    /** 焦点在退出搜索与页面暂停时释放，避免不可见输入继续持有键盘。 */
    val focusManager = LocalFocusManager.current
    /** 搜索动作只操作键盘，不触发扫描或持久化。 */
    val keyboard = LocalSoftwareKeyboardController.current
    /** IME 可见性来自系统 Insets，返回规则一次只处理一层。 */
    val imeVisible = WindowInsets.isImeVisible
    /** 聚焦只允许发生在当前页面前台，后台恢复不因数据重组反复弹键盘。 */
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    /** 真正离页沿用已有写入等待，搜索模式切换不能绕过它。 */
    val requestPageBack: () -> Unit = {
        focusManager.clearFocus()
        keyboard?.hide()
        if (viewModel.requestBack()) currentOnBack()
    }
    /** 搜索返回只清词和关闭输入，名单提交保持独立并继续执行。 */
    val closeSearch: () -> Unit = {
        viewModel.updateSearchQuery("")
        searchMode = ClipSourceFilterSearchMode.Browsing
        focusManager.clearFocus()
        keyboard?.hide()
    }
    /** 系统返回先收键盘，再退出搜索，普通态才请求离页。 */
    val requestBack: () -> Unit = {
        when (resolveClipSourceFilterBackAction(searchMode, imeVisible)) {
            ClipSourceFilterBackAction.DismissKeyboard -> keyboard?.hide()
            ClipSourceFilterBackAction.CloseSearch -> closeSearch()
            ClipSourceFilterBackAction.LeavePage -> requestPageBack()
        }
    }

    // 页面前台才启动扫描；暂停或离页只取消扫描任务，已接受写入由 ViewModel 继续完成。
    LifecycleResumeEffect(viewModel) {
        viewModel.onVisible()
        // 暂停同时释放输入焦点；回到前台只恢复扫描，不自动重开键盘。
        onPauseOrDispose {
            viewModel.onHidden()
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    // 系统返回不绕过仍在提交的单项事务。
    BackHandler(onBack = requestBack)
    // 仅模式切入搜索时启动一次；等待前台及下一帧输入挂载，离页或退出搜索自动取消。
    LaunchedEffect(searchMode, lifecycle) {
        if (searchMode == ClipSourceFilterSearchMode.Searching) {
            // state 是页面生命周期，不能用搜索按钮或系统开关代替可见性。
            lifecycle.currentStateFlow.first { state -> state.isAtLeast(Lifecycle.State.RESUMED) }
            withFrameNanos { }
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                searchFocusRequester.requestFocus()
                keyboard?.show()
            }
        }
    }
    // 返回等待状态只在所有写入成功后发出导航；失败撤销自动返回并留在页面提示。
    LaunchedEffect(writeState.navigateBack) {
        if (writeState.navigateBack) {
            focusManager.clearFocus()
            keyboard?.hide()
            currentOnBack()
        }
    }

    SecondaryPageScaffold(
        title = stringResource(R.string.base_general_clip_source_block_setting),
        onBack = requestBack,
        topBar = {
            if (searchMode == ClipSourceFilterSearchMode.Searching) {
                SearchTopBar(
                    query = installedState.searchQuery,
                    onQueryChange = viewModel::updateSearchQuery,
                    placeholder = stringResource(R.string.base_general_clip_source_block_search_hint),
                    backContentDescription = stringResource(com.cla.clip.master.R.string.host_exit_search),
                    clearContentDescription = stringResource(R.string.base_general_clear_search_keyword),
                    onBack = closeSearch,
                    // 清空保留模式和焦点；之前若收起键盘，明确清空动作可以再次开启输入。
                    onClear = {
                        viewModel.updateSearchQuery("")
                        searchFocusRequester.requestFocus()
                        keyboard?.show()
                    },
                    // 实时筛选不需要另起任务，IME 搜索仅收起键盘。
                    onSubmit = { keyboard?.hide() },
                    inputModifier = Modifier.focusRequester(searchFocusRequester),
                )
            } else {
                TitleBar(
                    title = stringResource(R.string.base_general_clip_source_block_setting),
                    onBack = requestPageBack,
                    actions = {
                        // 等待已接受写入后离页期间不再进入搜索，防止延迟导航与输入竞争。
                        IconButton(
                            enabled = !writeState.backRequested,
                            onClick = { searchMode = ClipSourceFilterSearchMode.Searching },
                        ) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.base_general_search))
                        }
                    },
                )
            }
        },
    ) { paddingValues ->
        // paddingValues 来自二级页面骨架，系统导航栏避让由骨架统一处理。
        Column(
            // 骨架负责系统导航区，页面只补 IME 占用，搜索时列表仍可滚动和勾选。
            Modifier.fillMaxSize().padding(paddingValues).imePadding(),
            verticalArrangement = Arrangement.spacedBy(ClipMasterThemeTokens.tokens.spacing.small),
        ) {
            if (settingsLoadFailed) {
                TextButton(onClick = viewModel::retrySettingsLoad) {
                    Text(stringResource(R.string.base_general_data_load_failed_retry))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ClipMasterThemeTokens.tokens.spacing.small),
            ) {
                // 数量按完整提交名单和最近成功扫描统计，窄屏允许左侧换行，右侧胶囊保留热区。
                Text(
                    text = clipSourceBlockHeaderSubtitle(blockedPackages.orEmpty().size, installedState),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
                // 胶囊只表达显示范围，两态视觉不同，继续连接既有状态与扫描入口。
                ToggleFilterChip(
                    label = stringResource(com.cla.clip.master.R.string.host_clip_source_filter_system_apps),
                    accessibilityDescription = stringResource(R.string.base_general_clip_source_block_show_system_apps),
                    selected = installedState.showSystemApps,
                    onSelectedChange = viewModel::updateShowSystemApps,
                )
            }
            // 有旧列表才在顶部补充读取失败；无可见行的失败提示由列表区域居中承载。
            if (installedState.unavailableReasonCode != null && listContentState == ClipSourceBlockListContentState.Content) {
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
                // 无行时中央显示加载，避免首次加载同时出现顶部刷新与中央两个进度指示。
                isRefreshing = installedState.loading && listContentState == ClipSourceBlockListContentState.Content,
                onRefresh = viewModel::refreshInstalledApps,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                // 始终保留可滚动容器，空结果和不足一屏也能下拉；包名稳定 key 保持分组后的行身份。
                LazyColumn(Modifier.fillMaxSize()) {
                    if (listContentState == ClipSourceBlockListContentState.Content) {
                        items(visibleCandidates, key = { candidate -> candidate.packageName }) { candidate ->
                            // candidate 只适配共享行；优先展示当前行提交状态，其余行可独立操作。
                            SelectableAppRow(
                                // 图像只属于当前组合项，离场取消加载并释放局部结果。
                                icon = { AppIcon(candidate.packageName, iconLoader, Modifier.size(40.dp)) },
                                state = SelectableAppRowState(
                                    title = candidate.appName,
                                    subtitle = candidate.packageName,
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
                    } else {
                        item(key = "list-content-state") {
                            // 全视口状态项使提示位于可用列表中央，同时保留 LazyColumn 下拉手势。
                            CenteredStateContent(Modifier.fillParentMaxSize().padding(horizontal = 20.dp)) {
                                when (listContentState) {
                                    ClipSourceBlockListContentState.Loading -> InlineLoadingState(stringResource(R.string.base_general_loading))
                                    ClipSourceBlockListContentState.Empty -> Text(
                                        stringResource(R.string.base_general_clip_source_block_empty_candidates),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    ClipSourceBlockListContentState.Failed -> TextButton(
                                        // 明确重试只重读安装列表，不修改已保存规则或清空搜索。
                                        onClick = viewModel::refreshInstalledApps,
                                    ) {
                                        Text(stringResource(R.string.base_general_data_load_failed_retry))
                                    }
                                    // 此分支只渲染非列表形态，Content 不生成占位文案。
                                    ClipSourceBlockListContentState.Content -> Unit
                                }
                            }
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
