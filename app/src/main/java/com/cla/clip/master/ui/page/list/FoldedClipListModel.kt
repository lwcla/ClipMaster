package com.cla.clip.master.ui.page.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.map
import com.cla.clip.base.general.entity.ClipVisibilityScope
import com.cla.clip.base.general.entity.toUi
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.master.processor.ClipboardDataProcessor
import com.cla.clip.master.processor.DefaultClipboardDataProcessor
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * 折叠剪贴列表页 ViewModel。
 *
 * 负责提供只包含折叠记录的分页流，并复用通用剪贴操作处理器完成复制、置顶、删除和取消折叠；
 * 页面可见后才会收集分页流，避免折叠页不可见时持续观察数据库。
 */
@HiltViewModel
class FoldedClipListModel @Inject constructor(
    /** 通用剪贴数据处理器；折叠页不直接操作 Repository，保持和普通列表一致的剪贴操作边界。 */
    private val clipboardDataProcessor: DefaultClipboardDataProcessor,

    /** 剪贴数据仓库使用 Lazy，避免 ViewModel 创建时立即初始化数据库依赖。 */
    private val clipRepository: Lazy<ClipRepository>,
) : ViewModel(), ClipboardDataProcessor by clipboardDataProcessor {

    /**
     * 折叠剪贴记录分页数据。
     *
     * 查询范围固定为 `FoldedOnly`，排序规则仍由 DAO 统一维护为置顶优先、时间倒序。
     */
    val pagedClips = Pager(
        config = PagingConfig(
            // 折叠页和普通列表保持同样分页大小，避免同一共享 item 在不同页面出现加载节奏差异。
            pageSize = 20,
            // 距离底部 5 个 item 时预加载，快速浏览折叠数据时减少空白等待。
            prefetchDistance = 5,
            // 折叠列表没有占位统计需求，关闭占位减少测量和侧滑状态复杂度。
            enablePlaceholders = false
        )
    ) {
        clipRepository.get().loadClips(ClipVisibilityScope.FoldedOnly)
    }.flow.map { pagingData -> pagingData.map { clipDetail -> clipDetail.toUi() } }.cachedIn(
        CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO)
    )
}
