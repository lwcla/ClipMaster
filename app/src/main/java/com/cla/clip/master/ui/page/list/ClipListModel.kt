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
import com.cla.clip.master.utils.ShizukuConnector
import com.cla.clip.master.processor.ClipboardDataProcessor
import com.cla.clip.master.processor.DefaultClipboardDataProcessor
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * 剪贴列表页 ViewModel。
 *
 * 负责提供首页剪贴记录分页流，并通过 `ClipboardDataProcessor` 复用复制、删除、置顶等通用剪贴操作。
 * Shizuku 连接只在列表页触发，因为列表是用户最先感知剪贴监听状态的入口。
 */
@HiltViewModel
class ClipListModel @Inject constructor(
    /** Shizuku 服务连接器，用于在页面需要时拉起跨进程剪贴板监听能力。 */
    private val shizukuConnector: ShizukuConnector,

    /** 通用剪贴数据处理器；委托后页面层不需要关心 Repository 和剪贴板 API 细节。 */
    private val clipboardDataProcessor: DefaultClipboardDataProcessor,

    /** 剪贴数据仓库使用 Lazy，避免 ViewModel 初始化时立刻触发数据库依赖创建。 */
    private val clipRepository: Lazy<ClipRepository>,
) : ViewModel() , ClipboardDataProcessor by clipboardDataProcessor {

    /**
     * 首页剪贴记录分页数据。
     *
     * 查询结果在 IO 调度器上缓存到 ViewModel 生命周期内，避免 Compose 重组或生命周期恢复时重复创建分页查询。
     */
    val pagedClips = Pager(
        config = PagingConfig(
            // 每页 20 条兼顾数据库读取开销和竖向列表首屏填充速度，后续如卡片内容变重再评估。
            pageSize = 20,
            // 距离底部 5 个 item 时预加载，减少快速滑动时的空白感。
            prefetchDistance = 5,
            // 剪贴记录没有稳定总数展示需求，关闭占位能减少列表测量和占位渲染复杂度。
            enablePlaceholders = false
        )
    ) {
        clipRepository.get().loadClips(ClipVisibilityScope.VisibleOnly)
    }.flow.map { it.map { data -> data.toUi() } }.cachedIn(
        CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO)
    )

    /**
     * 尝试连接 Shizuku 剪贴板服务。
     *
     * 这里不直接暴露服务对象给 UI，页面只表达“需要连接”，具体权限、绑定和失败兜底由连接器处理。
     */
    fun connectShizuku(){
        shizukuConnector.connect()
    }
}
