package com.cla.clip.master.processor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.cla.clip.base.general.R
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.base.general.utils.toast
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

interface ClipboardDataProcessor {

    /** 删除成功的事件 */
    val deleteSuccessFlow: SharedFlow<Long>

    /** 复制到剪贴板 */
    fun copyToClipboard(clip: ClipShowEntity)

    /** 复制到剪贴板 */
    fun copyToClipboard(content: String)

    /**
     * 删除剪贴item
     * @param clip 要删除的分组中的任何一个Clip条目。
     * @param sendEvent 是否发送删除成功的消息
     */
    fun deleteClip(clip: ClipShowEntity, sendEvent: Boolean = false)

    /** 更新置顶状态 */
    fun updatePinStatus(clip: ClipShowEntity, isPinned: Boolean)
}

class DefaultClipboardDataProcessor @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    @param:VmScope private val scope: CoroutineScope,
    private val clipRepository: Lazy<ClipRepository>
) : ClipboardDataProcessor {

    val clipboardManager by lazy { appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    private val _deleteSuccessFlow = MutableSharedFlow<Long>()
    override val deleteSuccessFlow = _deleteSuccessFlow.asSharedFlow()

    override fun copyToClipboard(clip: ClipShowEntity) {
        scope.launch(Dispatchers.IO) {
            clipRepository.get().updateTimestamp(clip.id)
        }

        copyToClipboard(clip.content)
    }

    override fun copyToClipboard(content: String) {
        val clipData = ClipData.newPlainText("ClipMaster", content)
        clipboardManager.setPrimaryClip(clipData)

        val text = if (content.length > 10) {
            content.take(10) + "..."
        } else {
            content
        }

        scope.launch {
            appContext.toast("${appContext.getString(R.string.base_general_copied)}${text}")
        }
    }

    override fun deleteClip(clip: ClipShowEntity, sendEvent: Boolean) {
        scope.launch(Dispatchers.IO) {
            if (clipRepository.get().deleteClip(clip) && sendEvent) {
                _deleteSuccessFlow.emit(clip.id)
            }
        }
    }

    override fun updatePinStatus(clip: ClipShowEntity, isPinned: Boolean) {
        scope.launch(Dispatchers.IO) {
            clipRepository.get().updatePinStatus(clip.id, isPinned)
        }
    }
}