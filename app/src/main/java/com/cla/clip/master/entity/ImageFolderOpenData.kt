package com.cla.clip.master.entity

/**
 * 图片下载结果通知携带的目录打开数据。
 *
 * 图片批量下载完成后不需要进入业务详情页，而是直接尝试打开本批次保存目录；timestamp 用于区分同一个目录的多次通知点击，
 * 避免 Compose 重组或 Activity 复用时把新点击误判为已经消费过的旧事件。
 */
data class ImageFolderOpenData(
    /** 本批次图片保存目录，通常是 `DCIM/clipMaster/<网页标题>` 这类公共媒体相对路径；为空时只能走相册兜底。 */
    val outputDir: String?,

    /** 通知点击事件时间戳，用于一次性消费判断，必须来自通知创建时的单调时间。 */
    val timestamp: Long,
)
