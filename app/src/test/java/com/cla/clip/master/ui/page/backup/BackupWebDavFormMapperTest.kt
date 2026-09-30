package com.cla.clip.master.ui.page.backup

import com.cla.clip.base.general.backup.settings.BackupSettingsSnapshot
import com.cla.clip.base.general.backup.settings.WebDavConnectionSnapshot
import com.cla.clip.base.general.backup.settings.WebDavPasswordUpdate
import org.junit.Assert.assertEquals
import org.junit.Test

/** 保护密码回填和异步加载边界；所有凭据均为合成数据，不调用系统 Keystore。 */
class BackupWebDavFormMapperTest {
    /** 用户只修改账号时仍提交输入框里的回填密码，由真实连接判断认证结果。 */
    @Test
    fun changedUsernameSubmitsCurrentPassword() {
        /** 回填后修改账号的合成表单，密码没有重新输入。 */
        val state = BackupUiState(webDavEndpoint = "https://backup.example", webDavUsername = "new-account", webDavPassword = "filled-secret")
        /** 仓库实际接收的关联配置，不允许将密码退回保留旧目标的特殊动作。 */
        val input = state.toWebDavSettingsInput()
        assertEquals("new-account", input.username)
        assertEquals(WebDavPasswordUpdate.Replace("filled-secret"), input.passwordUpdate)
    }

    /** 用户更换服务地址时按当前输入提交，不额外要求密码编辑事件。 */
    @Test
    fun changedEndpointSubmitsCurrentPassword() {
        /** 已改为新服务器、仍保留回填密码的合成表单。 */
        val state = BackupUiState(webDavEndpoint = "https://new.example", webDavUsername = "account", webDavPassword = "filled-secret")
        /** 提交的新服务器地址必须与输入框当前密码成组保存。 */
        val input = state.toWebDavSettingsInput()
        assertEquals("https://new.example", input.endpoint)
        assertEquals(WebDavPasswordUpdate.Replace("filled-secret"), input.passwordUpdate)
    }

    /** 清空输入框是提交空密码，不能悄悄沿用旧密文或要求专用移除按钮。 */
    @Test
    fun emptyPasswordIsSubmittedInsteadOfRetainingOldCredential() {
        /** 用户已经删空密码文本，但地址与账号仍然有效。 */
        val state = BackupUiState(webDavEndpoint = "https://backup.example", webDavUsername = "account", webDavPassword = "")
        /** 空字符串也必须进入仓库的明确替换契约。 */
        val input = state.toWebDavSettingsInput()
        assertEquals(WebDavPasswordUpdate.Replace(""), input.passwordUpdate)
    }

    /** 打开页面必须回填已保存密码，同时使用与密码同份快照的认证目标。 */
    @Test
    fun savedPasswordIsFilledWithMatchingTarget() {
        /** 合成的已提交目标，避免测试读取真实用户配置。 */
        val connection = WebDavConnectionSnapshot(
            BackupSettingsSnapshot(webDavEndpoint = "https://backup.example", webDavUsername = "account", hasWebDavPassword = true),
            "synthetic-secret",
        )
        /** 新页面状态经过实际回填规则后的可观察表单。 */
        val state = BackupUiState().withWebDavConnection(connection, preserveDraft = false)
        assertEquals("synthetic-secret", state.webDavPassword)
        assertEquals("https://backup.example", state.webDavEndpoint)
        assertEquals("account", state.webDavUsername)
    }

    /** 后台读取晚于用户输入完成时，不能用旧密码覆盖用户的新输入。 */
    @Test
    fun loadingDoesNotOverwriteEditedPassword() {
        /** 用户正在编辑的目标和密码草稿。 */
        val draft = BackupUiState(webDavEndpoint = "https://new.example", webDavUsername = "new-account", webDavPassword = "new-secret")
        /** 读取仍返回旧目标的已保存凭据。 */
        val connection = WebDavConnectionSnapshot(BackupSettingsSnapshot(hasWebDavPassword = true), "old-secret")
        /** 保护草稿后，地址、账号和密码应仍是用户本次输入。 */
        val state = draft.withWebDavConnection(connection, preserveDraft = true)
        assertEquals("new-secret", state.webDavPassword)
        assertEquals("https://new.example", state.webDavEndpoint)
        assertEquals("new-account", state.webDavUsername)
    }

    /** 用户手动清空的密码不能被迟到的旧凭据复活。 */
    @Test
    fun loadingDoesNotUndoPasswordClearing() {
        /** 用户已经删空输入框，但尚未正式提交的页面状态。 */
        val draft = BackupUiState(webDavPassword = "")
        /** 空密码尚未保存，因此异步读取仍可得到旧密码。 */
        val connection = WebDavConnectionSnapshot(BackupSettingsSnapshot(hasWebDavPassword = true), "old-secret")
        /** 回填必须尊重用户已经清空的输入。 */
        val state = draft.withWebDavConnection(connection, preserveDraft = true)
        assertEquals("", state.webDavPassword)
    }

    /** 空密码提交成功后的回填必须保持空文本，不能重新展示旧密码。 */
    @Test
    fun committedEmptyPasswordStaysEmpty() {
        /** 旧密码仍在先前状态中，用于发现回填遗漏密码字段的回归。 */
        val draft = BackupUiState(webDavPassword = "old-secret")
        /** 已加密保存的空密码仍有密文，但解密结果必须为空字符串。 */
        val connection = WebDavConnectionSnapshot(BackupSettingsSnapshot(hasWebDavPassword = true), "")
        /** 提交完成后使用新快照替换表单。 */
        val state = draft.withWebDavConnection(connection, preserveDraft = false)
        assertEquals("", state.webDavPassword)
    }

    /** 密文不可读取时仍展示认证目标，让用户能直接输入新密码修复。 */
    @Test
    fun unavailablePasswordKeepsTargetEditable() {
        /** 解密失败的兜底只丢弃明文，保留已提交配置及密文存在信息。 */
        val connection = WebDavConnectionSnapshot(
            BackupSettingsSnapshot(webDavEndpoint = "https://backup.example", webDavUsername = "account", hasWebDavPassword = true),
            null,
        )
        /** 失败兜底的回填不伪装成未配置的新安装。 */
        val state = BackupUiState().withWebDavConnection(connection, preserveDraft = false)
        assertEquals("", state.webDavPassword)
        assertEquals("https://backup.example", state.webDavEndpoint)
        assertEquals("account", state.webDavUsername)
    }
}
