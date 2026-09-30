package com.cla.clip.master.ui.page.backup

import com.cla.clip.base.general.backup.settings.WebDavConnectionSnapshot
import com.cla.clip.base.general.backup.settings.WebDavPasswordUpdate
import com.cla.clip.base.general.backup.settings.WebDavSettingsInput

/** 将同份已提交配置和密码回填到表单；编辑中的草稿不被异步加载覆盖，不持久化明文。 */
internal fun BackupUiState.withWebDavConnection(
    /** 同一次读取取得的目标与密码；密码为空表示未设置或读取失败后的可编辑兜底。 */
    connection: WebDavConnectionSnapshot,
    /** 用户已经开始编辑时保留全部草稿，包括手动清空的密码，避免旧凭据被重新回填。 */
    preserveDraft: Boolean,
): BackupUiState {
    // 异步读取完成时重新检查编辑状态，避免迟到的旧凭据覆盖新输入或复活已清空文本。
    if (preserveDraft) return this
    return copy(
        webDavEndpoint = connection.settings.webDavEndpoint,
        webDavUsername = connection.settings.webDavUsername,
        webDavPassword = connection.password.orEmpty(),
        webDavRemoteDir = connection.settings.webDavRemoteDir,
        webDavAllowHttp = connection.settings.webDavAllowInsecureHttp,
    )
}

/** 将表单当前值转成成组提交配置；密码字段包括用户清空后的空字符串，不校验账号是否变更。 */
internal fun BackupUiState.toWebDavSettingsInput(): WebDavSettingsInput = WebDavSettingsInput(
    endpoint = webDavEndpoint,
    username = webDavUsername,
    remoteDir = webDavRemoteDir,
    allowInsecureHttp = webDavAllowHttp,
    passwordUpdate = WebDavPasswordUpdate.Replace(webDavPassword),
)
