package com.cla.clip.master.onboarding

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 首次说明后的内部动作阶段，只保留于 Activity ViewModel，不成为设置中的新权限类型。 */
internal enum class FirstLaunchPermissionStep {
    /** 未选择或本轮引导已结束。 */ Done,
    /** 等待前台宿主发起 Shizuku 引导。 */ Shizuku,
    /** 已发起引导，等待授权回调或外部页面返回。 */ WaitingShizuku,
    /** 等待前台宿主申请通知。 */ Notification,
    /** 等待系统通知权限结果。 */ WaitingNotification,
}

/** 一次会话的权限序列；在主线程使用，旋转重建宿主不会重复消费已发起的动作。 */
internal class FirstLaunchPermissionSequence {
    /** 已提交首次选择只允许开始一轮自动引导。 */
    private var begun = false
    /** 当前一次性动作，外部不能直接改写。 */
    private val mutableStep = MutableStateFlow(FirstLaunchPermissionStep.Done)
    /** 宿主观察阶段并使用 take 方法消费。 */
    val step = mutableStep.asStateFlow()

    /** 暂不开启直接结束；同意且首次调用才进入 Shizuku。 */
    fun begin(accepted: Boolean) {
        if (begun) return
        begun = true
        if (accepted) mutableStep.value = FirstLaunchPermissionStep.Shizuku
    }

    /** 在执行系统动作前标记已消费，避免回调与重组重入。 */
    fun takeShizukuRequest(): Boolean {
        if (mutableStep.value != FirstLaunchPermissionStep.Shizuku) return false
        mutableStep.value = FirstLaunchPermissionStep.WaitingShizuku
        return true
    }

    /** 不区分授予或拒绝；两种结果都允许用户继续正常使用应用。 */
    fun shizukuFinished() {
        if (mutableStep.value == FirstLaunchPermissionStep.WaitingShizuku) mutableStep.value = FirstLaunchPermissionStep.Notification
    }

    /** 通知只能在上一动作结束后发起，重复调用无副作用。 */
    fun takeNotificationRequest(): Boolean {
        if (mutableStep.value != FirstLaunchPermissionStep.Notification) return false
        mutableStep.value = FirstLaunchPermissionStep.WaitingNotification
        return true
    }

    /** 系统通知结果只结束引导，不改变任何已提交的功能开关。 */
    fun notificationFinished() {
        if (mutableStep.value == FirstLaunchPermissionStep.WaitingNotification) mutableStep.value = FirstLaunchPermissionStep.Done
    }
}
