package com.cla.clip.feature.ad.uniad

/** uni-ad 广告释放保护器，保证多路径释放幂等。 */
internal class UniAdReleaseGuard {
    /** 是否已经执行过释放；只在当前广告请求生命周期内有效。 */
    private var released = false

    /**
     * 若尚未释放则执行释放动作。
     *
     * 返回 true 表示本次实际执行释放；false 表示之前已经释放过。
     */
    fun releaseOnce(onRelease: () -> Unit): Boolean {
        if (released) {
            return false
        }
        released = true
        onRelease()
        return true
    }

    /** 当前资源是否已经释放；迟到回调需要据此丢弃。 */
    fun isReleased(): Boolean = released
}
