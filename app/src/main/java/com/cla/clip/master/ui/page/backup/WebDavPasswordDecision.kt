package com.cla.clip.master.ui.page.backup

import com.cla.clip.base.general.backup.BackupFailure
import com.cla.clip.base.general.backup.settings.WebDavPasswordUpdate

/** 仅决定本次 WebDAV 密码提交动作，不解密、保存或长期缓存已存在的密码。 */
internal fun decideWebDavPasswordUpdate(
    /** 当前已提交服务地址，用于识别目标变化。 */
    savedEndpoint: String,
    /** 当前已提交登录名，切换账号也视为新认证目标。 */
    savedUsername: String,
    /** 用户本次草稿中的服务地址。 */
    draftEndpoint: String,
    /** 用户本次草稿中的登录名。 */
    draftUsername: String,
    /** 旧目标是否保存了设备绑定密文。 */
    hasSavedPassword: Boolean,
    /** 用户本次输入的密码，空值默认不修改。 */
    passwordDraft: String,
    /** 用户是否明确点击移除密码。 */
    clearRequested: Boolean,
): WebDavPasswordUpdate {
    if (clearRequested) return WebDavPasswordUpdate.Clear
    if (passwordDraft.isNotEmpty()) return WebDavPasswordUpdate.Replace(passwordDraft)
    // 服务地址或登录名变化后，不自动向新认证目标发送旧密码。
    if (hasSavedPassword && (savedEndpoint != draftEndpoint.trim() || savedUsername != draftUsername)) {
        throw BackupFailure.AuthenticationFailed()
    }
    return WebDavPasswordUpdate.Retain
}
