package com.cla.clip.master.ui.page.backup

import com.cla.clip.base.general.backup.BackupFailure
import com.cla.clip.base.general.backup.settings.WebDavPasswordUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 密码草稿只表达本次修改，不把旧密码解密回填到 UI。 */
class WebDavPasswordDecisionTest {
    /** 空草稿保留原密文，防止打开设置页就擦除密码。 */
    @Test
    fun emptyDraftRetainsSavedPassword() {
        /** 同一目标下的空输入代表未修改密码。 */
        val action = decideWebDavPasswordUpdate("https://backup.example", "account", "https://backup.example", "account", true, "", false)
        assertEquals(WebDavPasswordUpdate.Retain, action)
    }

    /** 目标变更不能自动带上旧目标的认证凭据。 */
    @Test
    fun changedTargetRequiresExplicitPasswordAction() {
        assertThrows(BackupFailure.AuthenticationFailed::class.java) {
            decideWebDavPasswordUpdate("https://old.example", "account", "https://new.example", "account", true, "", false)
        }
    }

    /** 同一服务地址切换账号时，也不能把旧账号密码自动发给新账号。 */
    @Test
    fun changedUsernameRequiresExplicitPasswordAction() {
        assertThrows(BackupFailure.AuthenticationFailed::class.java) {
            decideWebDavPasswordUpdate("https://backup.example", "old-account", "https://backup.example", "new-account", true, "", false)
        }
    }

    /** 明确移除动作优先于旧草稿，避免密码被错误再次加密。 */
    @Test
    fun explicitClearRemovesSavedPassword() {
        /** 用户确认的移除密码操作。 */
        val action = decideWebDavPasswordUpdate("https://old.example", "old-account", "https://new.example", "new-account", true, "", true)
        assertEquals(WebDavPasswordUpdate.Clear, action)
    }

    /** 用户输入新密码时必须按替换处理，而不能沿用旧密文。 */
    @Test
    fun nonEmptyDraftReplacesSavedPassword() {
        /** 新密码草稿仅在本次决策的结果中短暂存在。 */
        val action = decideWebDavPasswordUpdate("https://old.example", "old-account", "https://new.example", "new-account", true, "synthetic-secret", false)
        assertEquals(WebDavPasswordUpdate.Replace("synthetic-secret"), action)
    }
}
