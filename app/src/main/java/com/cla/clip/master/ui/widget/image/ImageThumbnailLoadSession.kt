package com.cla.clip.master.ui.widget.image

/** 单个请求模型的主线程事件门闩；模型替换/离场时关闭，防止旧组件回写页面。 */
internal class ImageThumbnailLoadSession(
    /** 事件接收方只处理加载事实，不在共享层操作业务选择。 */
    private val onEvent: (ImageThumbnailLoadEvent) -> Unit,
) {
    /** 当前真实加载尝试的内存身份，首次缓存命中也必须生成。 */
    private var attempt: Any? = null
    /** 每次尝试只发布一个终态，重复 painter 通知不重复汇总。 */
    private var completed = false
    /** 是否仍挂载；离场取消之后所有加载回调均无效。 */
    private var attached = true

    /** Lazy 子组合重新激活时恢复门闩；普通重组不调用，不制造新加载。 */
    fun open() {
        if (attached) return
        attached = true
        attempt = null
        completed = false
    }

    /** 由同一 painter 的加载开始回调触发，清除上次终态并生成新身份。 */
    fun loading() {
        if (!attached) return
        attempt = Any()
        completed = false
        onEvent(ImageThumbnailLoadEvent(checkNotNull(attempt), ImageThumbnailLoadPhase.Loading))
    }

    /** 发布成功或失败；缓存直接返回终态时先补开始，拒绝取消后和重复终态。 */
    fun complete(phase: ImageThumbnailLoadPhase) {
        require(phase != ImageThumbnailLoadPhase.Loading)
        if (!attached || completed) return
        if (attempt == null) loading()
        completed = true
        onEvent(ImageThumbnailLoadEvent(checkNotNull(attempt), phase))
    }

    /** 释放仅撤销写入许可，不发送成功/失败，不清除页面保存的失败证据。 */
    fun close() { attached = false }
}
