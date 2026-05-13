package com.cla.clip.master.entity

/**
 * 从通知栏提取的额外数据，包含一个ID和一个时间戳。
 * 这个数据类用于在MainVm中存储从通知栏提取的剪贴板ID和下载任务ID，确保每个ID只被使用一次，避免重复打开详情页或下载任务页。
 */
data class ExtraData(
    /** 通知携带的业务 id，可能是剪贴板记录 id 或视频下载任务 id，必须大于等于 0 才有导航意义。 */
    val id: Long,

    /** 通知事件时间戳，用于区分同一个业务 id 的不同点击事件，避免一次性消费判断误合并。 */
    val timestamp: Long
)
