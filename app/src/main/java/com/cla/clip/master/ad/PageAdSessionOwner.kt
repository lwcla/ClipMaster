package com.cla.clip.master.ad

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cla.clip.feature.ad.api.AdReleaseReason

/** 页面可见访问的会话所有者；将生命周期规则与 Compose/SDK 平台接线隔离。 */
internal class PageAdSessionOwner(
    /** 初次组合时的导航及 Tab 可见性，不包含业务资格。 */
    initiallyVisible: Boolean,
    /** 数据是否就绪等业务资格，变化不单独开启新访问。 */
    initiallyEnabled: Boolean,
    /** 初始转场可能只有 STARTED，此时允许保留但不能请求。 */
    initiallyResumed: Boolean = initiallyVisible,
) {
    /** 新访问替换整个对象，使旧槽释放回调只能影响旧账本；变化驱动宿主重组。 */
    var session by mutableStateOf(PageAdSession(initiallyVisible && initiallyEnabled).apply {
        canRequest = active && initiallyResumed
    })
        private set
    /** 记录真实可见边界，重复 RESUMED/重组不能重新请求。 */
    private var wasVisible = initiallyVisible
    /** 所有者销毁后拒绝迟到的生命周期更新。 */
    private var closed = false

    /** 离开释放旧会话，返回创建新身份；资格变化仅更新许可，同页失败不重试。 */
    fun update(visible: Boolean, enabled: Boolean, resumed: Boolean = visible,
        releaseReason: AdReleaseReason = AdReleaseReason.PageLeft) {
        if (closed) return
        if (visible && !wasVisible) {
            session = PageAdSession(active = enabled)
        }
        wasVisible = visible
        session.active = visible && enabled
        session.canRequest = session.active && resumed
        if (!visible) session.clear(releaseReason)
        else if (!enabled) session.clear(AdReleaseReason.Ineligible)
    }

    /** 组合离开时永久关闭，重复调用无副作用。 */
    fun close(reason: AdReleaseReason = AdReleaseReason.PageLeft) {
        closed = true
        session.active = false
        session.canRequest = false
        session.clear(reason)
    }
}
