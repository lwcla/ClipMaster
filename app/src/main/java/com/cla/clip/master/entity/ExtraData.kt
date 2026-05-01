package com.cla.clip.master.entity

/**
 * 从通知栏提取的额外数据，包含一个ID和一个时间戳。
 * 这个数据类用于在MainVm中存储从通知栏提取的剪贴板ID和下载任务ID，确保每个ID只被使用一次，避免重复打开详情页或下载任务页。
 */
data class ExtraData(
    val id: Long,
    val timestamp: Long
)