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
import kotlinx.coroutines.withContext
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

    /** 将指定剪贴记录批量移入回收站，完成后通过回调返回实际处理数量。 */
    fun moveClipsToRecycleBin(ids: Set<Long>, onComplete: (Int) -> Unit = {})

    /** 批量彻底删除指定剪贴记录，完成后通过回调返回实际处理数量。 */
    fun deleteClipsPermanently(ids: Set<Long>, onComplete: (Int) -> Unit = {})

    /** 更新置顶状态；true 会写入当前时间，false 会清零置顶时间。 */
    fun updatePinStatus(clip: ClipShowEntity, isPinned: Boolean)

    /** 更新折叠状态；折叠会记录折叠时间，取消折叠会清空折叠时间，不改变剪贴内容本身。 */
    fun updateFoldStatus(clip: ClipShowEntity, isFolded: Boolean)

    /** 批量折叠普通剪贴记录，完成后通过回调返回实际折叠数量。 */
    fun foldVisibleClips(ids: Set<Long>, onComplete: (Int) -> Unit = {})
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

    override fun moveClipsToRecycleBin(ids: Set<Long>, onComplete: (Int) -> Unit) {
        scope.launch(Dispatchers.IO) {
            /** 实际移入回收站数量；Repository 会过滤无效、重复和已删除 id。 */
            val count = clipRepository.get().moveClipsToRecycleBin(ids)
            if (count > 0) {
                BackupAutoScheduler.markDirtyAndSchedule(appContext)
                appContext.toast(appContext.getString(R.string.base_general_moved_count_to_recycle_bin, count))
            } else {
                appContext.toast(R.string.base_general_no_processable_clips)
            }
            withContext(Dispatchers.Main) {
                onComplete(count)
            }
        }
    }

    override fun deleteClipsPermanently(ids: Set<Long>, onComplete: (Int) -> Unit) {
        scope.launch(Dispatchers.IO) {
            /** 实际彻底删除数量；Repository 会过滤无效、重复和不存在的 id。 */
            val count = clipRepository.get().deleteClipsPermanently(ids)
            if (count > 0) {
                BackupAutoScheduler.markDirtyAndSchedule(appContext)
                appContext.toast(appContext.getString(R.string.base_general_deleted_count_permanently, count))
            } else {
                appContext.toast(R.string.base_general_no_processable_clips)
            }
            withContext(Dispatchers.Main) {
                onComplete(count)
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

    override fun foldVisibleClips(ids: Set<Long>, onComplete: (Int) -> Unit) {
        scope.launch(Dispatchers.IO) {
            /** 实际折叠数量；Repository/DAO 会跳过已删除、已折叠和不存在的记录。 */
            val count = clipRepository.get().foldVisibleClips(ids)
            if (count > 0) {
                BackupAutoScheduler.markDirtyAndSchedule(appContext)
                appContext.toast(appContext.getString(R.string.base_general_folded_clip_count, count))
            } else {
                appContext.toast(R.string.base_general_no_processable_clips)
            }
            withContext(Dispatchers.Main) {
                onComplete(count)
            }
        }
    }
}
