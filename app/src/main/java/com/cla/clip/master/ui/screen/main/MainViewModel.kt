package com.cla.clip.master.ui.screen.main

import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.entity.ClipData
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.master.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject


/**
 * 主屏幕的UI状态。
 *
 * @param pinnedClips 置顶的剪贴板列表。
 * @param latestClips 未置顶的最新剪贴板列表。
 * @param isLoading 是否正在加载初始数据。
 */
data class MainUiState(
    val pinnedClips: List<ClipData> = emptyList(),
    val latestClips: List<ClipData> = emptyList(),
    val isLoading: Boolean = true
)

/**
 * 主屏幕的ViewModel。
 * 使用 @HiltViewModel 注解，Hilt会自动处理它的创建和依赖注入。
 *
 * @param repository 通过构造函数注入的ClipRepository。
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: ClipRepository
) : BaseViewModel() {

    // 使用 combine 操作符将两个Flow（置顶列表和普通列表）合并成一个单一的UI状态。
    private val _uiState = combine(
        repository.getPinnedClips(),
        repository.getLatestClips()
    ) { pinned, latest ->
        MainUiState(
            pinnedClips = pinned,
            latestClips = latest,
            isLoading = false // 只要接收到任何一个Flow的更新，就认为初始加载已完成
        )
    }.stateIn(
        scope = viewModelScope,
        // 当有UI订阅时开始收集Flow，并在最后一个订阅者消失后5秒停止，以节省资源。
        started = SharingStarted.WhileSubscribed(5000),
        // Flow的初始值，表示正在加载中
        initialValue = MainUiState(isLoading = true)
    )

    /**
     * 向UI层暴露的不可变的UI状态。
     */
    val uiState: StateFlow<MainUiState> = _uiState

    /**
     * 删除一个完整的剪贴板分组（包括其所有历史记录）。
     * @param clip 要删除的分组中的任何一个Clip条目。
     */
    fun deleteClipGroup(clip: ClipData) {
        viewModelScope.launch {
            repository.deleteClipGroup(clip.groupId)
        }
    }

    /**
     * 更新一个Clip条目。
     * 可用于切换置顶状态、修改颜色等。
     * @param clip 更新后的Clip对象。
     */
    fun updateClip(clip: ClipData) {
        viewModelScope.launch {
            repository.upsertClip(clip)
        }
    }
}