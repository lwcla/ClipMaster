package com.cla.clip.master.ui.page.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 备份明文风险披露策略的纯逻辑测试。 */
class BackupPlaintextDisclosurePolicyTest {
    /** 尚未确认风险时，所有会生成备份的入口都必须先展示披露。 */
    @Test
    fun everyBackupCreationActionRequiresDisclosureBeforeAcceptance() {
        BackupPlaintextDisclosureAction.entries.forEach { action ->
            assertTrue(action.requiresDisclosure(accepted = false))
        }
    }

    /** 已确认风险后，备份入口应直接继续，避免重复打断用户。 */
    @Test
    fun acceptedDisclosureDoesNotBlockBackupCreationActions() {
        BackupPlaintextDisclosureAction.entries.forEach { action ->
            assertFalse(action.requiresDisclosure(accepted = true))
        }
    }
}
