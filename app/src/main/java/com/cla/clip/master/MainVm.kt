package com.cla.clip.master

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.cla.clip.base.general.utils.logI
import com.cla.clip.master.entity.ExtraData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
/**
 * 主 Activity 的一次性导航状态 ViewModel。
 *
 * 通知点击可能带来剪贴板记录 id 或下载任务 id；这些目标需要在 Compose 中被消费一次，
 * 否则重组、横竖屏或重复 intent 可能导致重复导航。
 */
class MainVm @Inject constructor() : ViewModel() {

    companion object {
        /** ViewModel 日志标签，用于排查通知目标是否被重复消费。 */
        private const val TAG = "MainVm"
    }

    /** 待打开的剪贴板记录 id，来自通知 intent；消费后会记录到 usedClipId，避免重复进入详情页。 */
    var pendingClipId by mutableStateOf<ExtraData?>(null)

    /** 最近一次已消费的剪贴板记录 id，带时间戳区分同一 id 的不同通知事件。 */
    private var usedClipId: ExtraData? = null

    /** 待打开的视频下载任务 id，来自下载结果通知；消费后会记录到 usedTaskId。 */
    var pendingTaskId by mutableStateOf<ExtraData?>(null)

    /** 最近一次已消费的下载任务 id，避免同一通知目标在重组时重复打开。 */
    private var usedTaskId: ExtraData? = null

    /**
     * 取出一个尚未消费的剪贴板记录 id。
     *
     * 返回 null 表示没有新目标或目标已被消费；调用方通常在 LaunchedEffect 中调用，拿到 id 后立即导航详情页。
     */
    fun pendingClipId(): Long? {
        if (usedClipId != pendingClipId) {
            usedClipId = pendingClipId
            return pendingClipId?.id
        }

        if (usedClipId != null && usedClipId == pendingClipId) {
            logI(TAG) { "pendingClipId: $pendingClipId 已经被使用过 usedClipId=$usedClipId" }
        }
        return null
    }

    /**
     * 取出一个尚未消费的视频下载任务 id。
     *
     * 返回 null 表示没有新下载目标或目标已被消费；调用方拿到 id 后进入下载结果页。
     */
    fun pendingTaskId(): Long? {
        if (usedTaskId != pendingTaskId) {
            usedTaskId = pendingTaskId
            return pendingTaskId?.id
        }

        if (usedTaskId != null && usedTaskId == pendingTaskId) {
            logI(TAG) { "pendingTaskId: $pendingTaskId 已经被使用过 usedTaskId=$usedTaskId" }
        }
        return null
    }
}
