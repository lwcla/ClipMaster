package com.cla.clip.feature.ad.admob

/** 主线程单次请求状态；SDK 回调必须先切回主线程再操作。 */
internal class AdmobRequestGuard {
    /** 加载阶段已结束后拒绝重复与迟到加载。 */
    private var loadingFinished = false
    /** 页面释放或隐私撤回后永久关闭当前 nonce。 */
    private var closed = false
    /** 诊断事件按请求去重，不保存广告素材。 */
    private val events = mutableSetOf<String>()
    /** 当前素材是否仍能交给页面持有。 */
    val canAcceptAd: Boolean get() = !closed && !loadingFinished
    /** 原子地领取加载终态；失败和超时也占用该终态。 */
    fun finishLoading(): Boolean {
        if (!canAcceptAd) return false
        loadingFinished = true
        return true
    }
    /** 只允许活跃请求上报尚未发送的事件。 */
    fun record(event: String): Boolean = !closed && events.add(event)
    /** 关闭幂等，返回本次是否首次关闭。 */
    fun close(): Boolean {
        if (closed) return false
        closed = true
        return true
    }
}
