package com.cla.clip.master.backup

import org.junit.Assert.*
import org.junit.Test

/** 自动调度纯规则，避免网络等待反向阻塞本地和失败包复活。 */
class BackupSchedulingPolicyTest {
    /** 验证 Scheduler 实际使用的滚动周期及自动/手动上传策略，真实生成约束留给平台验收。 */
    @Test fun dailyIntervalAndUploadPolicyAreIndependent() {
        assertEquals(24L, BackupSchedulingPolicy.intervalHours)
        assertTrue(BackupSchedulingPolicy.uploadRequiresUnmetered("auto", true))
        assertFalse(BackupSchedulingPolicy.uploadRequiresUnmetered("manual", true))
    }
    /** 已失败和运行中断的包不能被启动恢复自动重试。 */
    @Test fun onlyUnfinishedWaitingPhasesCanBeScheduled() {
        assertTrue(BackupSchedulingPolicy.canSchedule("ready"))
        assertTrue(BackupSchedulingPolicy.canSchedule("queued"))
        assertFalse(BackupSchedulingPolicy.canSchedule("failed"))
        assertFalse(BackupSchedulingPolicy.canSchedule("uploading"))
        // Worker 已结束而状态写盘失败时也不得在启动时重新上传。
        assertFalse(BackupSchedulingPolicy.canSchedule("queued", workFinished = true))
    }
}
