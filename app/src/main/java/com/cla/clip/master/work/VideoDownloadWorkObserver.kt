package com.cla.clip.master.work

import android.content.Context
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** 只观察指定视频任务的调度状态，不取消或启动任务；收集由下载页生命周期控制。 */
internal class VideoDownloadWorkObserver @Inject constructor(
    /** 应用上下文用于定位 WorkManager，不保存 Activity 或页面。 */
    @param:ApplicationContext private val context: Context,
) {
    /** 排队、受约束及正在执行均为活动任务；无记录或已终止才允许显示中断。 */
    fun observeActiveWork(taskId: Long): Flow<Boolean> = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(DownloadVideoWorker.uniqueWorkName(taskId))
        .map { records -> records.any { !it.state.isFinished } }
        .distinctUntilChanged()
}
