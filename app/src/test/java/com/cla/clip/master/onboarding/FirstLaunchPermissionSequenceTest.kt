package com.cla.clip.master.onboarding

import org.junit.Assert.*
import org.junit.Test

/** 只测试首次说明后的权限动作次序，真实系统授权由设备验收。 */
class FirstLaunchPermissionSequenceTest {
    /** 暂不开启不申请任何权限，不存在额外前台权限步骤。 */
    @Test fun deferringDoesNotRequestPermissions() {
        /** 新会话的动作序列。 */
        val sequence = FirstLaunchPermissionSequence()
        sequence.begin(accepted = false)
        assertEquals(FirstLaunchPermissionStep.Done, sequence.step.value)
        assertFalse(sequence.takeShizukuRequest())
        assertFalse(sequence.takeNotificationRequest())
    }

    /** 权限必须逐个发起，重复渲染或回调不得重放。 */
    @Test fun acceptanceRequestsShizukuThenNotificationOnlyOnce() {
        /** 新会话的动作序列。 */
        val sequence = FirstLaunchPermissionSequence()
        sequence.begin(true)
        assertFalse(sequence.takeNotificationRequest())
        assertTrue(sequence.takeShizukuRequest())
        assertFalse(sequence.takeShizukuRequest())
        sequence.shizukuFinished()
        sequence.shizukuFinished()
        assertTrue(sequence.takeNotificationRequest())
        assertFalse(sequence.takeNotificationRequest())
        sequence.notificationFinished()
        assertEquals(FirstLaunchPermissionStep.Done, sequence.step.value)
        sequence.begin(true)
        assertFalse(sequence.takeShizukuRequest())
    }
}
