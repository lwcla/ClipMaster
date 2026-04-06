package com.cla.clip.master

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.R
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.repository.ClipDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

abstract class BaseViewModel : ViewModel() {

    @Inject
    @ApplicationContext
    lateinit var appContext: Context

    @Inject
    lateinit var clipDao: ClipDao

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
            clipDao.updateTimestamp(clip.id)
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

        Toast.makeText(appContext, "${appContext.getString(R.string.base_general_copied)}${text}", Toast.LENGTH_SHORT).show()
    }

    /**
     * 删除剪贴item
     * @param clip 要删除的分组中的任何一个Clip条目。
     * @param sendEvent 是否发送删除成功的消息
     */
    fun deleteClip(clip: ClipShowEntity, sendEvent: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            if (clipDao.deleteClip(clip) && sendEvent) {
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
            clipDao.updatePinStatus(clip.id, isPinned)
        }
    }
}