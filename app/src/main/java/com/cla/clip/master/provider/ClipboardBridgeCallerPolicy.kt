package com.cla.clip.master.provider

import android.os.Process

/**
 * Provider 调用方校验策略。
 *
 * 该策略单独抽出，便于单元测试覆盖安全边界；真正的 Binder 调用方 UID 由 Provider 入口传入。
 */
object ClipboardBridgeCallerPolicy {
    /**
     * 判断调用方是否允许触发剪贴板读取。
     *
     * @param callingUid Binder 调用方 UID；只接受 shell/root，避免任意第三方 App 调用导出的 Provider。
     */
    fun isAllowed(callingUid: Int): Boolean {
        return callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID
    }
}
