package com.cla.clip.master.ui.page.list

import com.cla.clip.master.ui.widget.sourceapp.rememberAppIconLoader
import com.cla.clip.master.ui.widget.sourceapp.SourceAppNameLoadingEffect
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.cla.clip.base.general.config.ClipItemQuickAction
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.utils.logD
import com.cla.clip.master.ui.dialog.ClipBatchDeleteChoiceDialog
import com.cla.clip.master.ui.dialog.ClipDeleteChoiceDialog
import com.cla.clip.master.ui.navigation.DetailRoute
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.navigation.SearchRoute
import com.cla.clip.master.ui.widget.ClipBatchSelectionActionBar
import com.cla.clip.master.ui.widget.ShizukuServiceUnavailableTip
import com.cla.clip.master.ui.widget.TitleBar
import com.cla.clip.master.ui.widget.TitleBarText
import com.cla.clip.master.ui.widget.clip.ClipResultList
import com.cla.clip.master.ui.widget.clip.ClipSwipePastTexts

/**
 * 剪贴数据列表页
 *
 * 这个页面只保留列表页特有的生命周期、权限提示、删除弹窗和搜索入口；
 * 具体卡片渲染交给 `ClipResultList`，方便搜索页复用完全一致的结果样式。
 * 首页采用共享列表的无广告分支，不创建广告会话或占位；其他页面独立配置广告。
 *
 * @param viewModel Hilt 自动注入的列表 ViewModel。
 * @param listState 外部传入竖向列表状态，首页底部 Tab 再次点击列表时可以滚回顶部。
 * @param visibleToUser 首页稳定选中状态，仅约束来源名称和图标的可见加载。
 * @param onNavigate 统一页面跳转入口。
 */
@Composable
fun ClipListPage(
    viewModel: ClipListModel = hiltViewModel(),
    listState: LazyListState = rememberLazyListState(),
    // 首页横向 Pager 的稳定可见状态，避免“我的”页后台继续读取列表来源信息。
    visibleToUser: Boolean = true,
    onNavigate: (Route) -> Unit  // 跳转页面
) {
    // 页面级引用只控制历史名称查询；图标在卡片内按可见生命周期加载。
    /** 页面只创建一次加载边界，实际系统读取由每个可见卡片负责。 */
    val iconLoader = rememberAppIconLoader()
    SourceAppNameLoadingEffect(viewModel.sourceLoadGate, visibleToUser)
    /** 页面生命周期用于暂停非前台的分页收集，不由广告或 Tab 动画控制。 */
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    /** 复用 ViewModel 的分页流，仅在页面至少 STARTED 时收集列表内容。 */
    val pagedClips = remember(viewModel.pagedClips, lifecycle) {
        viewModel.pagedClips.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
    }.collectAsLazyPagingItems()
    /** 页面可见时读取已提交快捷动作；首次加载或读取失败暂时关闭快捷操作。 */
    val quickAction by viewModel.quickAction.collectAsStateWithLifecycle(initialValue = ClipItemQuickAction.None)

    /** 待单条删除的记录，仅在当前组合内持有，关闭弹窗后清空。 */
    var deleteClip by remember { mutableStateOf<ClipShowEntity?>(null) }
    /** 当前多选态选中的剪贴 id；只存 id，分页刷新时不会因为对象实例变化丢失选择。 */
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    /** 当前是否处于批量多选态；和 selectedIds 分离，允许 0 选中时继续停留等待用户操作。 */
    var selectionMode by remember { mutableStateOf(false) }
    /** 批量动作执行中标记；用于禁用底部按钮，避免用户连点导致重复写库或重复弹窗。 */
    var isBatchActionRunning by remember { mutableStateOf(false) }
    /** 是否显示批量删除选择弹窗；弹窗只展示数量，不展示任何剪贴内容。 */
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    /** 当前选中数量；标题栏、底部栏和批量弹窗共用该值，避免不同区域显示不一致。 */
    val selectedCount = selectedIds.size
    /** 批量操作按钮是否可用；0 选中和执行中都不能触发删除或折叠。 */
    val batchActionsEnabled = selectionMode && selectedCount > 0 && !isBatchActionRunning

    /** 退出多选态并清空选择；返回键、取消按钮、操作完成和查询变化都复用这一个出口。 */
    fun clearSelection() {
        selectionMode = false
        selectedIds = emptySet()
        isBatchActionRunning = false
        showBatchDeleteDialog = false
    }

    /** 长按进入多选并选中当前剪贴记录；如果已经在多选态则保持原选择集合并补上当前 id。 */
    fun enterSelection(clip: ClipShowEntity) {
        selectionMode = true
        selectedIds = selectedIds + clip.id
    }

    /** 多选态点击 item 时切换选中状态；非多选态不会调用该方法。 */
    fun toggleSelection(clip: ClipShowEntity) {
        selectedIds = if (clip.id in selectedIds) {
            selectedIds - clip.id
        } else {
            selectedIds + clip.id
        }
    }

    BackHandler(enabled = selectionMode) {
        clearSelection()
    }

    logD("MainPage", { "MainPage: pagedClips itemCount = ${pagedClips.itemCount}, loadState = ${pagedClips.loadState}" })

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (selectionMode) {
                TitleBar(
                    navigation = {
                        TextButton(onClick = { clearSelection() }) {
                            Text(stringResource(com.cla.clip.base.general.R.string.base_general_cancel))
                        }
                    },
                    title = {
                        TitleBarText(text = stringResource(com.cla.clip.base.general.R.string.base_general_selected_count, selectedCount))
                    }
                )
            } else {
                TitleBar(
                    navigation = {},
                    title = {
                        TitleBarText(text = stringResource(com.cla.clip.base.general.R.string.base_general_list))
                    },
                    actions = {
                        IconButton(onClick = { onNavigate(SearchRoute()) }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(com.cla.clip.base.general.R.string.base_general_search)
                            )
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (selectionMode) {
                ClipBatchSelectionActionBar(
                    selectedText = stringResource(com.cla.clip.base.general.R.string.base_general_selected_count, selectedCount),
                    deleteText = stringResource(com.cla.clip.base.general.R.string.base_general_delete),
                    foldText = stringResource(com.cla.clip.base.general.R.string.base_general_fold_clip),
                    enabled = batchActionsEnabled,
                    onDelete = { showBatchDeleteDialog = true },
                    onFold = {
                        /** 本次要折叠的 id 快照；执行期间选择集合可能被 UI 清空，数据库操作仍使用这份稳定快照。 */
                        val idsToFold = selectedIds
                        isBatchActionRunning = true
                        viewModel.foldVisibleClips(idsToFold) {
                            clearSelection()
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ShizukuServiceUnavailableTip()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 列表区域只占用权限提示下方的剩余空间，避免子级 fillMaxSize 反向撑开整个 Column。
                        .weight(1f)
                ) {
                    // 首页复用默认无广告入口，让列表占满可用空间且不触发广告请求。
                    ClipResultList(
                        iconLoader = iconLoader,
                        iconsVisibleToUser = visibleToUser,
                        listState = listState,
                        pagedClips = pagedClips,
                        emptyText = stringResource(com.cla.clip.base.general.R.string.base_general_clip_list_empty),
                        // 首页列表底部保留统一的轻量留白，让最后一条记录和底部导航之间有明确但不过分的呼吸感。
                        contentPadding = PaddingValues(start = 10.dp, top = 10.dp, end = 10.dp, bottom = if (selectionMode) 96.dp else 12.dp),
                        onPinToggle = { viewModel.updatePinStatus(it, !it.isPinned) },
                        onDelete = { clip -> deleteClip = clip },
                        onCopy = { viewModel.copyToClipboard(it) },
                        onSwipePastAction = { clip -> viewModel.updateFoldStatus(clip, true) },
                        // 继续与松手文案成对传入，阈值及提示阶段由共享卡片统一维护。
                        swipePastTexts = ClipSwipePastTexts(
                            continueText = stringResource(com.cla.clip.base.general.R.string.base_general_continue_swipe_to_fold_clip),
                            releaseText = stringResource(com.cla.clip.base.general.R.string.base_general_release_to_fold_clip),
                        ),
                        quickAction = quickAction,
                        enableQuickAction = !selectionMode && quickAction != ClipItemQuickAction.None,
                        selectedIds = selectedIds,
                        selectionMode = selectionMode,
                        onToggleSelection = ::toggleSelection,
                        onClick = { clip ->
                            if (selectionMode) {
                                toggleSelection(clip)
                            } else {
                                onNavigate(DetailRoute(clip.id))
                            }
                        },
                        onLongClick = ::enterSelection,
                    )

                    ClipDeleteChoiceDialog(
                        clip = deleteClip,
                        onDismiss = { deleteClip = null },
                        onMoveToRecycleBin = viewModel::deleteClip,
                        onDeletePermanently = viewModel::deleteClipPermanently
                    )

                    ClipBatchDeleteChoiceDialog(
                        selectedCount = selectedCount,
                        visible = showBatchDeleteDialog,
                        onDismiss = { showBatchDeleteDialog = false },
                        onMoveToRecycleBin = {
                            /** 本次要移入回收站的 id 快照；弹窗关闭后即使 UI 状态变化，也不影响本次批量操作对象。 */
                            val idsToDelete = selectedIds
                            isBatchActionRunning = true
                            viewModel.moveClipsToRecycleBin(idsToDelete) {
                                clearSelection()
                            }
                        },
                        onDeletePermanently = {
                            /** 本次要彻底删除的 id 快照；只传 id，不读取或展示剪贴内容。 */
                            val idsToDelete = selectedIds
                            isBatchActionRunning = true
                            viewModel.deleteClipsPermanently(idsToDelete) {
                                clearSelection()
                            }
                        }
                    )
                }
            }
        }
    }
}
