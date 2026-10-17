package com.cla.clip.master.work

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import com.cla.clip.base.general.backup.settings.BackupResultTarget
import org.junit.Assert.*
import org.junit.Test

/** 验证实际入队使用的请求，避免仅测试未被接入的策略常量。 */
class BackupAutoSchedulerTest {
    /** 只有云端等待网络，两个请求只携带同一轮次身份。 */
    @Test fun uploadRequiresConnectedNetworkAndExponentialBackoff() {
        /** 上传实际请求，构建不调用 WorkManager 或启动网络。 */
        val upload = BackupAutoScheduler.createTargetRequest("synthetic", BackupResultTarget.WebDav)
        /** 本地请求保证离线也能保存。 */
        val local = BackupAutoScheduler.createTargetRequest("synthetic", BackupResultTarget.Local)
        assertEquals(NetworkType.CONNECTED, upload.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, upload.workSpec.backoffPolicy)
        assertEquals(60_000L, upload.workSpec.backoffDelayDuration)
        assertEquals(NetworkType.NOT_REQUIRED, local.workSpec.constraints.requiredNetworkType)
        assertEquals("synthetic", upload.workSpec.input.getString("round_id"))
        assertEquals("synthetic", local.workSpec.input.getString("round_id"))
    }
}
