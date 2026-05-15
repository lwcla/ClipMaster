package com.cla.clip.master.ui.page.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.flowWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.utils.logD
import com.cla.clip.master.ui.dialog.DeleteDialog
import com.cla.clip.master.ui.navigation.DetailRoute
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.navigation.SearchRoute
import com.cla.clip.master.ui.widget.ShizukuServiceUnavailableTip
import com.cla.clip.master.ui.widget.TopLevelTitleBar

/**
 * 剪贴数据列表页
 *
 * 这个页面只保留列表页特有的生命周期、权限提示、删除弹窗和搜索入口；
 * 具体卡片渲染交给 `ClipResultList`，方便搜索页复用完全一致的结果样式。
 *
 * @param viewModel Hilt 自动注入的列表 ViewModel。
 * @param gridState 外部传入瀑布流状态，首页底部 Tab 再次点击列表时可以滚回顶部。
 * @param onNavigate 统一页面跳转入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipListPage(
    viewModel: ClipListModel = hiltViewModel(),
    gridState: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    onNavigate: (Route) -> Unit  // 跳转页面
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pagedClips = remember(viewModel.pagedClips, lifecycle) {
        viewModel.pagedClips.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
    }.collectAsLazyPagingItems()

    // --- BottomSheet 状态管理 ---
    // 保存当前长按选中的 Clip，如果为 null 则不显示 Sheet
    var selectedClipForSheet by remember { mutableStateOf<ClipShowEntity?>(null) }
    val sheetState = rememberModalBottomSheetState()

    var deleteClip by remember { mutableStateOf<ClipShowEntity?>(null) }

    logD("MainPage", { "MainPage: pagedClips itemCount = ${pagedClips.itemCount}, loadState = ${pagedClips.loadState}" })

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        TopLevelTitleBar(title = stringResource(com.cla.clip.base.general.R.string.base_general_list))
        ShizukuServiceUnavailableTip()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 列表区域只占用权限提示下方的剩余空间，避免子级 fillMaxSize 反向撑开整个 Column。
                .weight(1f)
        ) {
            ClipResultList(
                gridState = gridState,
                pagedClips = pagedClips,
                emptyText = stringResource(com.cla.clip.base.general.R.string.base_general_clip_list_empty),
                // 列表页右下角增加搜索按钮后，底部多留空间，避免最后一行卡片被悬浮按钮遮挡。
                contentPadding = PaddingValues(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 96.dp),
                onPinToggle = { viewModel.updatePinStatus(it, !it.isPinned) },
                onDelete = { clip -> deleteClip = clip },
                onCopy = { viewModel.copyToClipboard(it) },
                onClick = { onNavigate(DetailRoute(it.id)) },
                onLongClick = { clip ->
                    // 长按时，设置选中的 Clip，触发 BottomSheet 显示
//                selectedClipForSheet = clip
                },
            )

            FloatingActionButton(
                onClick = { onNavigate(SearchRoute) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = stringResource(com.cla.clip.base.general.R.string.base_general_search)
                )
            }

            DeleteDialog(
                clip = deleteClip,
                onDismiss = { deleteClip = null },
                onConfirmDelete = { clip ->
                    viewModel.deleteClip(clip)
                }
            )

            // 底部弹出窗口
            val clip = selectedClipForSheet
            if (clip != null) {
                ModalBottomSheet(
                    onDismissRequest = { selectedClipForSheet = null },
                    sheetState = sheetState
                ) {
                    // BottomSheet 的内容
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 添加垂直滚动修饰符
                            .verticalScroll(rememberScrollState())
                            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                    ) {
                        Text(
                            text = clip.content,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}
