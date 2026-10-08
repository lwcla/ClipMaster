package com.cla.clip.master.backup

import org.junit.Assert.assertEquals
import org.junit.Test

/** 明确失败提示必须基于稳定证据，不能靠耗时或截图猜测。 */
class BackupFailureMessageMapperTest {
    /** 旧中断与明确超时/配额各有独立分类。 */
    @Test fun interruptionAndInfrastructureErrorsAreActionable() {
        assertEquals(BackupFailureMessage.Interrupted, BackupFailureMessageMapper.map("upload_interrupted"))
        assertEquals(BackupFailureMessage.Interrupted, BackupFailureMessageMapper.map("generation_interrupted"))
        assertEquals(BackupFailureMessage.Interrupted, BackupFailureMessageMapper.map("local_interrupted"))
        assertEquals(BackupFailureMessage.Timeout, BackupFailureMessageMapper.map("upload_interrupted_timeout"))
        assertEquals(BackupFailureMessage.Quota, BackupFailureMessageMapper.map("upload_interrupted_quota"))
        assertEquals(BackupFailureMessage.Network, BackupFailureMessageMapper.map("upload_interrupted_network"))
        assertEquals(BackupFailureMessage.DeviceState, BackupFailureMessageMapper.map("upload_interrupted_device"))
        assertEquals(BackupFailureMessage.Cancelled, BackupFailureMessageMapper.map("upload_interrupted_cancelled"))
        assertEquals(BackupFailureMessage.Schedule, BackupFailureMessageMapper.map("schedule_failed"))
        assertEquals(BackupFailureMessage.Handoff, BackupFailureMessageMapper.map("handoff_failed"))
        assertEquals(BackupFailureMessage.Auth, BackupFailureMessageMapper.map("auth_failed"))
        assertEquals(BackupFailureMessage.Unknown, BackupFailureMessageMapper.map("unexpected_error"))
    }
}
