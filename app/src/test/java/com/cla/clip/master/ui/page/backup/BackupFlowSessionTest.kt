package com.cla.clip.master.ui.page.backup

import com.cla.clip.base.general.backup.RemoteBackupFile
import org.junit.Assert.*
import org.junit.Test

/** 保护图会话的请求所有权与关闭契约。 */
class BackupFlowSessionTest {
    /** 待接管请求不能被第二次点击覆盖，接管后同一恢复目的地仍独占会话。 */
    @Test
    fun pendingAndClaimedRequestsRejectReplacement() {
        /** 当前会话及两个合成目标不依赖真实网络、文件或 Android URI。 */
        val session = BackupFlowSession()
        /** 前一请求或会话，用于验证旧身份不会污染后续流程。 */
        val first = request()
        /** 后一请求或会话，独立保存新一轮目标。 */
        val second = request()
        assertTrue(session.submitRequest(first))
        assertFalse(session.submitRequest(second))
        assertSame(first, session.requests.value)
        assertTrue(session.acknowledgeRequest(first.requestId))
        assertNull(session.requests.value)
        assertFalse(session.acknowledgeRequest(first.requestId))
        assertFalse(session.submitRequest(second))
        session.finishRestore(first.requestId)
        assertTrue(session.submitRequest(second))
    }

    /** 旧确认或旧清理不能移除新恢复流程，避免转场期间旧 VM 干扰新 VM。 */
    @Test
    fun staleAcknowledgementAndCleanupCannotClearNewRequest() {
        /** 两个合成请求分别代表先后两次进入恢复页。 */
        val session = BackupFlowSession()
        /** 前一请求或会话，用于验证旧身份不会污染后续流程。 */
        val first = request()
        /** 后一请求或会话，独立保存新一轮目标。 */
        val second = request()
        assertTrue(session.submitRequest(first))
        assertFalse(session.acknowledgeRequest(second.requestId))
        assertFalse(session.clearPendingRequest(second.requestId))
        assertTrue(session.acknowledgeRequest(first.requestId))
        session.finishRestore(first.requestId)
        assertTrue(session.submitRequest(second))
        session.finishRestore(first.requestId)
        assertSame(second, session.requests.value)
    }

    /** 退出转场期间重复完成只允许第一次领取弹栈许可，不能继续弹出组外页面。 */
    @Test
    fun groupClosePermissionCanOnlyBeClaimedOnce() {
        /** 同一会话模拟连续两次旧页面完成回调。 */
        val session = readySession()
        assertTrue(session.close())
        assertFalse(session.close())
    }

    /** 两次打开导航组的请求槽相互独立，关闭后拒绝新请求与迟到接管。 */
    @Test
    fun sessionsRemainIsolatedAndClosedSessionRejectsRequests() {
        // 第一个会话已经接管请求，第二个会话仍可接受自己的目标。
        val first = readySession()
        // 新图会话没有旧实例的请求状态。
        val second = BackupFlowSession()
        // 仅使用合成目标，不打开文件或网络。
        val pending = request()
        assertNull(second.requests.value)
        assertTrue(second.submitRequest(pending))
        assertTrue(first.close())
        assertFalse(first.submitRequest(request()))
        assertFalse(first.acknowledgeRequest(pending.requestId))
        assertSame(pending, second.requests.value)
        assertTrue(second.close())
        assertNull(second.requests.value)
        assertFalse(second.acknowledgeRequest(pending.requestId))
    }

    /** 构造已接管恢复请求的会话，所有状态通过真实请求接管接口建立。 */
    private fun readySession(): BackupFlowSession {
        /** 合成请求只携带无效的测试文件名，不访问服务端。 */
        val session = BackupFlowSession()
        /** 合成恢复目标，不访问真实文件或服务端。 */
        val request = request()
        assertTrue(session.submitRequest(request))
        assertTrue(session.acknowledgeRequest(request.requestId))
        return session
    }

    /** 合成远端目标用于纯请求测试，不含真实地址、账号或授权 URI。 */
    private fun request(): BackupRestoreRequest = BackupRestoreRequest.WebDav(
        RemoteBackupFile("synthetic.zip", "synthetic.zip", null, null)
    )
}
