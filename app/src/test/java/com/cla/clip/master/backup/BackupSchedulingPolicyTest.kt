package com.cla.clip.master.backup

import org.junit.Assert.*
import org.junit.Test

/** 自动调度纯规则，避免网络等待反向阻塞本地和失败包复活。 */
class BackupSchedulingPolicyTest {
    /** 进程退出遗留 uploading 可由系统重执行；终态、未知及已结束 WorkSpec 不能复活。 */
    @Test fun systemReentryAcceptsOnlyUnfinishedWebDavPhases() {
        assertTrue(BackupSchedulingPolicy.canExecuteWebDavUpload("uploading"))
        assertTrue(BackupSchedulingPolicy.canExecuteWebDavUpload("ready"))
        assertTrue(BackupSchedulingPolicy.canExecuteWebDavUpload("queued"))
        // phase 表示各终态或非法状态，不依赖平台调度。
        for (phase in listOf("failed", "succeeded", "cancelled", "result_store_failed", "unselected", "generating", "unknown")) {
            assertFalse(phase, BackupSchedulingPolicy.canExecuteWebDavUpload(phase))
        }
        assertFalse(BackupSchedulingPolicy.canExecuteWebDavUpload("uploading", true))
        assertFalse(BackupSchedulingPolicy.canSchedule("uploading"))
    }
    /** 验证 Scheduler 实际使用的滚动周期及自动/手动上传策略，真实生成约束留给平台验收。 */
    @Test fun dailyIntervalAndUploadPolicyAreIndependent() {
        assertEquals(24L, BackupSchedulingPolicy.intervalHours)
        assertFalse(BackupSchedulingPolicy.uploadRequiresNetwork)
        assertFalse(BackupSchedulingPolicy.generationRequiresNetwork)
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
