package com.cla.clip.master.ui.page.main

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.map
import com.cla.clip.base.general.entity.ClipEntity
import com.cla.clip.base.general.entity.toUi
import com.cla.clip.base.general.logD
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.master.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject


/**
 * 主屏幕的ViewModel。
 * 使用 @HiltViewModel 注解，Hilt会自动处理它的创建和依赖注入。
 *
 * @param repository 通过构造函数注入的ClipRepository。
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: ClipRepository
) : BaseViewModel() {

    private val clipboardManager by lazy { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    val pagedClips = Pager(
        config = PagingConfig(
            pageSize = 20,       // 每页加载数量
            prefetchDistance = 5, // 距离底部多少个item时开始预加载
            enablePlaceholders = false
        )
    ) {
        repository.loadAllClips()
    }.flow.map { it.map { data ->
        data.toUi() .also {
            logD("MainViewModel") { "pagedClips toUi: $it" }
        }
    } }.cachedIn(
        CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO)
    )

    /**
     * 删除一个完整的剪贴板分组（包括其所有历史记录）。
     * @param clip 要删除的分组中的任何一个Clip条目。
     */
    fun deleteClipGroup(clip: ClipEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteClip(clip)
        }
    }

    /**
     * 更新置顶状态
     *
     * @param clip
     * @param isPinned 是否置顶
     */
    fun updatePinStatus(clip: ClipEntity, isPinned: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updatePinStatus(clip.id, isPinned)
        }
    }

    /**
     * 复制到剪贴板
     *
     * @param clip
     */
    fun copyToClipboard(clip: ClipEntity) {
        val clipData = android.content.ClipData.newPlainText("ClipMaster", clip.content)
        clipboardManager.setPrimaryClip(clipData)

        if (clip.content.length > 10) {
            Toast.makeText(context, "已复制:${clip.content.take(10)}...", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "已复制:${clip.content}", Toast.LENGTH_SHORT).show()
        }
    }
}