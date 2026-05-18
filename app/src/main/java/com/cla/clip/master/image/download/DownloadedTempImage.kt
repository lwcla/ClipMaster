package com.cla.clip.master.image.download

import com.cla.clip.base.general.dao.ImageExtractItemData
import java.io.File

/**
 * 已完成下载和内容校验的临时图片。
 *
 * 该结构是“下载阶段”和“发布阶段”的边界对象：只有进入这里的图片才允许写入 MediaStore。
 * 它保留原始数据库图片项、临时文件路径和真实格式信息，发布器可以据此保持网页顺序、原始字节和最终 MIME 一致。
 */
internal data class DownloadedTempImage(
    /** 原始图片项记录，用于按 displayOrder 排序、写日志和回写最终状态。 */
    val item: ImageExtractItemData,

    /** 下载到应用缓存目录的临时文件；发布器只复制它的原始字节，不做 Bitmap 重编码。 */
    val tempFile: File,

    /** 文件头识别后的真实 MIME，用于创建 MediaStore 记录，不能只依赖响应头或 URL 后缀。 */
    val mimeType: String,

    /** 真实格式对应的文件扩展名，不包含点，用于生成 001.gif、002.webp 等最终文件名。 */
    val extension: String,

    /** 是否识别到动图标记；只用于日志和诊断，不改变发布流程。 */
    val isAnimated: Boolean,

    /** 动图总时长，单位毫秒；为空表示静态图或当前格式无法可靠读取。 */
    val durationMs: Long?,
)
