package com.cla.clip.master

import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.R
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.repository.ClipDao
import com.cla.clip.base.general.utils.toast
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

abstract class BaseViewModel(open val appContext: Context) : ViewModel() {


}

abstract class ClipBaseVm(appContext: Context) : BaseViewModel(appContext) {

    @Inject
    lateinit var clipDao: Lazy<ClipDao>

    protected val clipboardManager by lazy { appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    private val _deleteSuccessFlow = MutableSharedFlow<Long>()
    val deleteSuccessFlow = _deleteSuccessFlow.asSharedFlow()

    /**
     * 复制到剪贴板
     *
     * @param clip
     */
    fun copyToClipboard(clip: ClipShowEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            clipDao.get().updateTimestamp(clip.id)
        }

        copyToClipboard(clip.content)
    }

    /** 复制到剪贴板 */
    fun copyToClipboard(content: String) {
        val clipData = android.content.ClipData.newPlainText("ClipMaster", content)
        clipboardManager.setPrimaryClip(clipData)

        val text = if (content.length > 10) {
            content.take(10) + "..."
        } else {
            content
        }

        viewModelScope.launch {
            appContext.toast("${appContext.getString(R.string.base_general_copied)}${text}")
        }
    }

    /**
     * 删除剪贴item
     * @param clip 要删除的分组中的任何一个Clip条目。
     * @param sendEvent 是否发送删除成功的消息
     */
    fun deleteClip(clip: ClipShowEntity, sendEvent: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            if (clipDao.get().deleteClip(clip) && sendEvent) {
                _deleteSuccessFlow.emit(clip.id)
            }
        }
    }

    /**
     * 更新置顶状态
     *
     * @param clip
     * @param isPinned 是否置顶
     */
    fun updatePinStatus(clip: ClipShowEntity, isPinned: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            clipDao.get().updatePinStatus(clip.id, isPinned)
        }
    }
}