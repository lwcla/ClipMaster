package com.cla.clip.feature.ad.admob

import android.content.Context
import android.view.MotionEvent
import android.view.KeyEvent
import android.widget.FrameLayout

/** 每次组合绑定独享的外壳；暂停/弹窗期间屏蔽触摸，不修改 SDK 注册的点击监听。 */
internal class AdmobNativeContainer(context: Context) : FrameLayout(context) {
    /** 即时许可在事件分发时读取，避免生命周期事件先于 Compose 重组的时间差。 */
    var canInteract: () -> Boolean = { false }
    /** 原始焦点策略恢复时使用，不抢回用户已经移到其他控件的焦点。 */
    private val normalFocusability = descendantFocusability
    /** 记录外壳原始无障碍模式，恢复后继续由 SDK 暴露广告资产。 */
    private val normalAccessibility = importantForAccessibility

    /** 临时阻塞也覆盖键盘和无障碍入口，不覆盖 SDK 子控件原有点击监听。 */
    fun updateInteraction(enabled: Boolean) {
        descendantFocusability = if (enabled) normalFocusability else FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = if (enabled) normalAccessibility else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (!enabled) clearFocus()
    }

    /** 阻塞时消费触摸，防止传给 SDK 或落到下面的业务卡片。 */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        if (canInteract()) super.dispatchTouchEvent(event) else true

    /** DPAD/键盘确认不能绕过多选或暂停期间的触摸门控。 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (canInteract()) super.dispatchKeyEvent(event) else true

    /** TalkBack 探索手势在阻塞期间不落到广告子树。 */
    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        if (canInteract()) super.dispatchHoverEvent(event) else true
}
