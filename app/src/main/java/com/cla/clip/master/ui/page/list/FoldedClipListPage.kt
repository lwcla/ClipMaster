package com.cla.clip.master.ui.page.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import com.cla.clip.master.ui.dialog.ClipDeleteChoiceDialog
import com.cla.clip.master.ui.navigation.DetailRoute
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.navigation.SearchRoute
import com.cla.clip.master.ui.navigation.SearchScope
import com.cla.clip.master.ui.widget.TitleBar

/**
 * 折叠数据列表页。
 *
 * 页面只展示已折叠剪贴记录，并复用普通列表的共享 item；这里额外提供搜索入口进入同一个搜索页的折叠范围，
 * 以及继续右滑取消折叠的交互，让折叠数据可以完整管理但不污染普通列表和普通搜索。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldedClipListPage(
    viewModel: FoldedClipListModel = hiltViewModel(),
    onBack: () -> Unit,
    onNavigate: (Route) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pagedClips = remember(viewModel.pagedClips, lifecycle) {
        viewModel.pagedClips.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
    }.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    var deleteClip by remember { mutableStateOf<ClipShowEntity?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TitleBar(
            title = stringResource(com.cla.clip.base.general.R.string.base_general_folded_clips),
            onBack = onBack
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            ClipResultList(
                listState = listState,
                pagedClips = pagedClips,
                emptyText = stringResource(com.cla.clip.base.general.R.string.base_general_folded_clip_list_empty),
                contentPadding = PaddingValues(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 96.dp),
                onPinToggle = { viewModel.updatePinStatus(it, !it.isPinned) },
                onDelete = { clip -> deleteClip = clip },
                onCopy = viewModel::copyToClipboard,
                onSwipePastAction = { clip -> viewModel.updateFoldStatus(clip, false) },
                swipePastActionText = stringResource(com.cla.clip.base.general.R.string.base_general_continue_swipe_to_unfold_clip),
                onClick = { onNavigate(DetailRoute(it.id)) },
                onLongClick = {},
            )

            FloatingActionButton(
                onClick = { onNavigate(SearchRoute(SearchScope.FoldedOnly)) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = stringResource(com.cla.clip.base.general.R.string.base_general_search_folded_clips)
                )
            }

            ClipDeleteChoiceDialog(
                clip = deleteClip,
                onDismiss = { deleteClip = null },
                onMoveToRecycleBin = viewModel::deleteClip,
                onDeletePermanently = viewModel::deleteClipPermanently
            )
        }
    }
}
