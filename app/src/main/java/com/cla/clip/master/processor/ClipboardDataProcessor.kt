package com.cla.clip.master.processor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.cla.clip.base.general.R
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.base.general.utils.toast
import com.cla.clip.master.work.BackupAutoScheduler
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 剪贴板列表/详情页共用的操作处理接口。
 *
 * 封装复制、删除和置顶操作，让页面不直接持有 ClipboardManager 或 Repository；删除成功事件用于列表局部刷新/提示。
 */
interface ClipboardDataProcessor {

    /** 删除成功的事件 */
    val deleteSuccessFlow: SharedFlow<Long>

    /** 复制完整剪贴板记录内容，并顺便刷新该记录时间戳，让它回到列表靠前位置。 */
    fun copyToClipboard(clip: ClipShowEntity)

    /** 将纯文本写入系统剪贴板，并展示复制成功提示。 */
    fun copyToClipboard(content: String)

    /**
     * 删除剪贴item
     * @param clip 要删除的分组中的任何一个Clip条目。
     * @param sendEvent 是否发送删除成功的消息
     */
    fun deleteClip(clip: ClipShowEntity, sendEvent: Boolean = false)

    /** 彻底删除剪贴 item，不进入回收站。 */
    fun deleteClipPermanently(clip: ClipShowEntity, sendEvent: Boolean = false)

    /** 更新置顶状态；true 会写入当前时间，false 会清零置顶时间。 */
    fun updatePinStatus(clip: ClipShowEntity, isPinned: Boolean)

    /** 更新折叠状态；折叠会记录折叠时间，取消折叠会清空折叠时间，不改变剪贴内容本身。 */
    fun updateFoldStatus(clip: ClipShowEntity, isFolded: Boolean)
}

/**
 * 默认剪贴板操作处理器。
 *
 * 使用 ViewModel 级协程作用域执行数据库操作，ClipboardManager 操作保持在当前进程内完成；该类不负责读取系统剪贴板新内容。
 */
class DefaultClipboardDataProcessor @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    @param:VmScope private val scope: CoroutineScope,
    private val clipRepository: Lazy<ClipRepository>
) : ClipboardDataProcessor {

    /** 系统剪贴板服务，懒加载避免未使用复制功能时提前获取系统服务。 */
    val clipboardManager by lazy { appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    /** 删除成功事件内部流，只有删除数据库成功且调用方要求发送事件时才 emit。 */
    private val _deleteSuccessFlow = MutableSharedFlow<Long>()

    /** 删除成功事件外部只读流，事件值为被删除的剪贴板记录 id。 */
    override val deleteSuccessFlow = _deleteSuccessFlow.asSharedFlow()

    override fun copyToClipboard(clip: ClipShowEntity) {
        scope.launch(Dispatchers.IO) {
            clipRepository.get().updateTimestamp(clip.id)
            BackupAutoScheduler.markDirtyAndSchedule(appContext)
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
            val success = clipRepository.get().deleteClip(clip)
            if (success) {
                BackupAutoScheduler.markDirtyAndSchedule(appContext)
                if (sendEvent) {
                    _deleteSuccessFlow.emit(clip.id)
                }
                appContext.toast(appContext.getString(R.string.base_general_moved_to_recycle_bin))
            }
        }
    }

    override fun deleteClipPermanently(clip: ClipShowEntity, sendEvent: Boolean) {
        scope.launch(Dispatchers.IO) {
            val success = clipRepository.get().deleteClipPermanently(clip)
            if (success) {
                BackupAutoScheduler.markDirtyAndSchedule(appContext)
                if (sendEvent) {
                    _deleteSuccessFlow.emit(clip.id)
                }
                appContext.toast(appContext.getString(R.string.base_general_deleted_permanently))
            }
        }
    }

    override fun updatePinStatus(clip: ClipShowEntity, isPinned: Boolean) {
        scope.launch(Dispatchers.IO) {
            clipRepository.get().updatePinStatus(clip.id, isPinned)
            BackupAutoScheduler.markDirtyAndSchedule(appContext)
        }
    }

    override fun updateFoldStatus(clip: ClipShowEntity, isFolded: Boolean) {
        scope.launch(Dispatchers.IO) {
            clipRepository.get().updateFoldStatus(clip.id, isFolded)
            BackupAutoScheduler.markDirtyAndSchedule(appContext)
        }
    }
}
