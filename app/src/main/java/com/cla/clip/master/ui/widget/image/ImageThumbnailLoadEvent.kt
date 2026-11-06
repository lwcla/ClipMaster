package com.cla.clip.master.ui.widget.image

/** 缩略图本次请求的阶段；仅用于内存 UI 通知，不作为下载或序列化协议。 */
internal enum class ImageThumbnailLoadPhase {
    /** 已开始真实请求，不能按失败处理。 */
    Loading,
    /** 同一缩略图请求已成功解码。 */
    Succeeded,
    /** 同一缩略图请求明确失败，取消不进入此状态。 */
    Failed,
}

/** 只传递尝试身份和阶段，避免共享组件上报 URL、请求头或异常原文。 */
internal class ImageThumbnailLoadEvent(
    /** 每次加载新建的内存身份，同一尝试的开始和终态必须使用同一对象。 */
    val attempt: Any,
    /** 来源为同一 Coil painter 的已确认阶段，取消不产生失败事件。 */
    val phase: ImageThumbnailLoadPhase,
)
