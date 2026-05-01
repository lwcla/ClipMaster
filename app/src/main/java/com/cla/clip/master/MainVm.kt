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
class MainVm @Inject constructor() : ViewModel() {

    companion object {
        private const val TAG = "MainVm"
    }

    var pendingClipId by mutableStateOf<ExtraData?>(null)
    private var usedClipId: ExtraData? = null

    var pendingTaskId by mutableStateOf<ExtraData?>(null)
    private var usedTaskId: ExtraData? = null

    /** 返回一个待处理的剪贴板ID，如果有的话，并且这个ID还没有被使用过。避免重复打开详情页 */
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

    /** 返回一个待处理的下载任务ID，如果有的话，并且这个ID还没有被使用过。避免重复打开下载任务页 */
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