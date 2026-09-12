package com.cla.clip.master.ui.page.backup

/**
 * 会生成未加密备份包的用户动作。
 *
 * 恢复、预览和连接测试不会创建新的备份文件，因此不属于这里的披露范围。
 */
internal enum class BackupPlaintextDisclosureAction {
    /** 通过系统文件选择器导出本地 ZIP。 */
    ManualLocal,

    /** 生成 ZIP 并手动上传到 WebDAV。 */
    ManualWebDav,

    /** 开启可能写入本地目录或 WebDAV 的自动备份。 */
    EnableAuto,
}

/** 已接受披露后不再重复打断；动作类型仍保留为显式参数，防止新入口绕过策略。 */
internal fun BackupPlaintextDisclosureAction.requiresDisclosure(
    /** 本机是否已经持久化用户的首次风险确认。 */
    accepted: Boolean,
): Boolean {
    return !accepted
}
