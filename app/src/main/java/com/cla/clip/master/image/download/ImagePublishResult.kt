package com.cla.clip.master.image.download

/**
 * 图片发布阶段汇总结果。
 *
 * 发布阶段只统计真正写入相册/公共目录的结果；下载阶段的过滤和失败由 Worker 另行合并，
 * 这样批次最终状态可以准确区分“下载失败”“发布失败”和“内容过滤”。
 */
internal data class ImagePublishResult(
    /** 成功发布到 MediaStore 或旧系统公共目录的图片数量。 */
    val successCount: Int,

    /** 临时文件已经下载成功但写入目标媒体库失败的数量。 */
    val failedCount: Int,
)
