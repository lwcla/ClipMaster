package com.cla.clip.master.onboarding

import com.cla.clip.shizuku.ShizukuUtils
import org.junit.Assert.*
import org.junit.Test
import rikka.shizuku.Shizuku

/** SDK 不重放授权结果；会话级监听必须在旧宿主消失、新宿主尚未挂载时仍接收结果。 */
class FirstLaunchShizukuSubscriptionTest {
    /** 授权发生在两个宿主之间也只能推进一次通知。 */
    @Test fun grantBetweenHostsAdvancesNotificationOnce() { verifyResultBetweenHosts(granted = true) }

    /** 拒绝发生在两个宿主之间同样不能卡住后续通知。 */
    @Test fun denialBetweenHostsAdvancesNotificationOnce() { verifyResultBetweenHosts(granted = false) }

    /** 用可控 SDK 事件源验证真实会话订阅，UI 重建不负责注册或移除监听。 */
    private fun verifyResultBetweenHosts(granted: Boolean) {
        /** 与 Activity ViewModel 一样跨宿主重建保留的权限序列。 */
        val sequence = FirstLaunchPermissionSequence()
        /** 仅模拟 SDK 的即时事件派发，不为晚注册者重放。 */
        val listeners = mutableListOf<Shizuku.OnRequestPermissionResultListener>()
        /** 验证拒绝不会连接服务，重复结果不会重复启动连接。 */
        var connections = 0
        /** 订阅属于会话，与 UI 是否观察状态无关。 */
        val subscription = FirstLaunchShizukuSubscription(sequence, { listeners.add(it) }, { listeners.remove(it) }, { connections++ })
        sequence.begin(true)
        // 旧宿主已发起请求后离开，仍由会话接收结果；不执行任何宿主回调。
        assertTrue(sequence.takeShizukuRequest())
        assertEquals(1, listeners.size)
        listeners.single().onRequestPermissionResult(ShizukuUtils.REQUEST_CODE, if (granted) 0 else -1)
        listeners.single().onRequestPermissionResult(ShizukuUtils.REQUEST_CODE, if (granted) 0 else -1)
        // 新宿主只需读取当前阶段，无需再申请 Shizuku 或等待已丢失的 SDK 结果。
        assertEquals(FirstLaunchPermissionStep.Notification, sequence.step.value)
        assertTrue(sequence.takeNotificationRequest())
        assertFalse(sequence.takeNotificationRequest())
        assertEquals(if (granted) 1 else 0, connections)
        subscription.close()
        assertTrue(listeners.isEmpty())
    }
}
