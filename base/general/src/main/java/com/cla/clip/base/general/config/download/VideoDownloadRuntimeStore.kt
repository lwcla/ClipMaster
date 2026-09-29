package com.cla.clip.base.general.config.download

import androidx.datastore.preferences.core.Preferences
import com.cla.clip.base.general.config.datastore.HostSettingsKeys
import com.cla.clip.base.general.config.datastore.HostSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** 最近视频下载任务指针快照；-1 表示没有可清理任务。 */
data class VideoDownloadRuntimeSnapshot(
    /** 最近下载任务 ID，交给下载域判断 pending 输出。 */
    val taskId: Long,
)

/** 视频下载轻量运行状态，仅保存清理上次 pending 输出所需任务指针。 */
class VideoDownloadRuntimeStore @Inject internal constructor(
    /** 宿主唯一 DataStore 介质。 */
    private val store: HostSettingsStore,
) {
    /** 已提交任务指针流；不因其他设置更新重复发射。 */
    val data: Flow<VideoDownloadRuntimeSnapshot> = store.data.map(::mapSnapshot).distinctUntilChanged()

    /** 读取最近任务 ID；IO 失败向调用方传播以阻止不安全清理。 */
    suspend fun snapshot(): VideoDownloadRuntimeSnapshot = mapSnapshot(store.snapshot())

    /** 等待任务指针落盘后返回，-1 用于明确清空。 */
    suspend fun setTaskId(value: Long) {
        store.edit { preferences -> preferences[HostSettingsKeys.VIDEO_DOWNLOAD_TASK_ID] = value }
    }

    /** 将原始设置映射为单一运行状态。 */
    private fun mapSnapshot(preferences: Preferences): VideoDownloadRuntimeSnapshot {
        return VideoDownloadRuntimeSnapshot(preferences[HostSettingsKeys.VIDEO_DOWNLOAD_TASK_ID] ?: NO_TASK_ID)
    }

    companion object {
        /** 未记录下载任务时的稳定哨兵值。 */
        const val NO_TASK_ID = -1L
    }
}
