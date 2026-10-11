package com.cla.clip.master.ui.page.list

import com.cla.clip.master.ui.widget.sourceapp.rememberAppIconLoader
import com.cla.clip.master.ui.widget.sourceapp.SourceAppNameLoadingEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.lifecycle.flowWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.master.ui.dialog.ClipDeleteChoiceDialog
import com.cla.clip.master.ui.navigation.DetailRoute
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.navigation.SearchRoute
import com.cla.clip.master.ui.navigation.SearchScope
import com.cla.clip.master.ui.widget.SecondaryPageScaffold
import com.cla.clip.master.ui.widget.clip.ClipCardTimeMode
import com.cla.clip.feature.ad.api.AdPlacement
import com.cla.clip.master.ad.rememberPageAdSession
import com.cla.clip.master.ad.adInteractionGuard
import com.cla.clip.master.ui.widget.clip.ClipListAdConfig
import com.cla.clip.master.ui.widget.clip.ClipResultList

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
    // 页面级引用只控制历史名称查询；图标在卡片内按可见生命周期加载。
    /** 页面只创建一次加载边界，实际系统读取由每个可见卡片负责。 */
    val iconLoader = rememberAppIconLoader()
    SourceAppNameLoadingEffect(viewModel.sourceLoadGate)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pagedClips = remember(viewModel.pagedClips, lifecycle) {
        viewModel.pagedClips.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
    }.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    var deleteClip by remember { mutableStateOf<ClipShowEntity?>(null) }

    // 页面广告身份不依赖内容数量，分页追加和置顶排序保持同一会话。
    val adSession = rememberPageAdSession("folded-list")
    // 确认、设置及批量操作覆盖层禁止广告交互并延后布局变化。
    val adOverlayBlocked = deleteClip != null

    SecondaryPageScaffold(
        modifier = Modifier.adInteractionGuard(adSession),
        title = stringResource(com.cla.clip.base.general.R.string.base_general_folded_clips),
        onBack = onBack,
        actions = {
            IconButton(onClick = { onNavigate(SearchRoute(SearchScope.FoldedOnly)) }) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = stringResource(com.cla.clip.base.general.R.string.base_general_search_folded_clips)
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            ClipResultList(
                iconLoader = iconLoader,
                adConfig = ClipListAdConfig(
                    session = adSession,
                    placement = AdPlacement.FoldedFeedNative,
                    suppressed = adOverlayBlocked,
                    layoutBlocked = adOverlayBlocked,
                ),
                listState = listState,
                pagedClips = pagedClips,
                emptyText = stringResource(com.cla.clip.base.general.R.string.base_general_folded_clip_list_empty),
                // 折叠列表和首页普通列表保持一致，在底部导航或系统手势区上方保留统一的轻量留白。
                contentPadding = PaddingValues(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 12.dp),
                // 折叠范围保留置顶操作能力，DAO 会先排置顶数据，再在分组内按 foldedAt 倒序。
                onPinToggle = { viewModel.updatePinStatus(it, !it.isPinned) },
                onDelete = { clip -> deleteClip = clip },
                onCopy = viewModel::copyToClipboard,
                onSwipePastAction = { clip -> viewModel.updateFoldStatus(clip, false) },
                swipePastActionText = stringResource(com.cla.clip.base.general.R.string.base_general_continue_swipe_to_unfold_clip),
                timeMode = ClipCardTimeMode.FoldedTime,
                onClick = { onNavigate(DetailRoute(it.id)) },
                onLongClick = {},
            )

            ClipDeleteChoiceDialog(
                clip = deleteClip,
                onDismiss = { deleteClip = null },
                onMoveToRecycleBin = viewModel::deleteClip,
                onDeletePermanently = viewModel::deleteClipPermanently
            )
        }
    }
}
