package com.cla.clip.master.image.format

/**
 * 已识别出的真实图片格式。
 *
 * 下载 Worker 根据该结构决定最终文件扩展名、MediaStore MIME 和动图诊断日志；它只描述已经落盘的真实字节，
 * 不参与图片转码，也不依赖响应头或 URL 后缀的表面信息。
 */
data class ImageFileFormat(
    /** 规范化后的图片 MIME 类型，例如 image/gif 或 image/webp；写入 MediaStore 供系统相册识别。 */
    val mimeType: String,

    /** 与 MIME 对应的文件扩展名，不包含点；用于生成 001.gif、002.webp 这类最终文件名。 */
    val extension: String,

    /** 是否在真实文件字节中识别到 GIF 多帧、WebP/APNG/AVIF 动画标记；用于定位相册静态展示问题。 */
    val isAnimated: Boolean = false,

    /** 可解析出的动画总时长，单位毫秒；为空只表示无法识别，不代表文件一定是静态。 */
    val durationMs: Long? = null,
)
