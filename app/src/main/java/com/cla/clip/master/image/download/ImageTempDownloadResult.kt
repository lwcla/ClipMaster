package com.cla.clip.master.image.download

import com.cla.clip.base.general.dao.ImageExtractItemData

/**
 * 单张图片临时下载结果。
 *
 * 下载阶段把结果分成成功、过滤和失败三类，Worker 只需要按类型计数和推进进度；
 * 过滤表示图片内容无效但请求链路正常，不应和网络异常、解码失败等真实失败混在一起。
 */
internal sealed interface ImageTempDownloadResult {
    /** 图片已落盘、格式已识别、内容质量已通过，可进入发布阶段。 */
    data class Success(val image: DownloadedTempImage) : ImageTempDownloadResult

    /** 图片被内容质量规则主动过滤，例如透明占位图、跟踪像素或纯色错误图。 */
    data class Filtered(
        /** 被过滤的原始图片项，供调用方必要时做统计或诊断。 */
        val item: ImageExtractItemData,

        /** 过滤原因，已经写入数据库单项状态；只用于内部诊断日志。 */
        val reason: String?,
    ) : ImageTempDownloadResult

    /** 图片下载或解析失败，属于真实失败计数。 */
    data class Failed(
        /** 下载失败的原始图片项，供调用方必要时做统计或诊断。 */
        val item: ImageExtractItemData,

        /** 失败异常；下载器已写入数据库单项状态，这里保留给上层诊断。 */
        val throwable: Throwable,
    ) : ImageTempDownloadResult
}
