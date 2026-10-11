package com.cla.clip.master.ui.page.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import com.cla.clip.master.paging.clipPagingConfig
import androidx.paging.cachedIn
import androidx.paging.map
import com.cla.clip.base.general.entity.ClipVisibilityScope
import com.cla.clip.base.general.entity.toUi
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.master.processor.ClipboardDataProcessor
import com.cla.clip.master.processor.DefaultClipboardDataProcessor
import com.cla.clip.master.sourceapp.ClipSourceNameAssembler
import com.cla.clip.master.sourceapp.SourceAppLoadGate
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
    /** 折叠分页仅补历史来源名称，不查询系统图标。 */
    private val sourceNames: ClipSourceNameAssembler,
) : ViewModel(), ClipboardDataProcessor by clipboardDataProcessor {
    /** 页面不可见时取消未完成名称查询，保留轻量结果等待复用。 */
    internal val sourceLoadGate = SourceAppLoadGate()


    /**
     * 折叠剪贴记录分页数据。
     *
     * 查询范围固定为 `FoldedOnly`，排序规则由 DAO 维护为置顶优先、组内折叠时间倒序，不受剪贴更新时间影响。
     */
    val pagedClips = Pager(
        config = clipPagingConfig()
    ) {
        clipRepository.get().loadClips(ClipVisibilityScope.FoldedOnly)
    }.flow.map { pagingData ->
        /** 当前代次复用名称查询，不观察来源表刷新卡片。 */
        val session = sourceNames.newSession()
        pagingData.map { clipDetail -> sourceLoadGate.run { session.attach(clipDetail.toUi()) } }
    }.cachedIn(
        CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO)
    )
}
