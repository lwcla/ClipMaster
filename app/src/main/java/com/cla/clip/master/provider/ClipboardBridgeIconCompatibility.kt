package com.cla.clip.master.provider

import com.cla.clip.shizuku.ClipboardBridgeContract

/** 旧图标命令的兼容边界：成功应答但拒绝同步，不接触文件、来源表或 Manager。 */
internal object ClipboardBridgeIconCompatibility {
    /** 查询与提交统一返回禁用状态，避免旧 Shizuku 进程继续上传或误报保存成功。 */
    fun result(): ClipboardBridgeResult = ClipboardBridgeResult.of(
        resultCode = ClipboardBridgeContract.CODE_OK,
        shouldSyncIcon = false,
        iconDecisionReason = "memory_icons_only",
        reasonCode = "memory_icons_only",
    )
}
