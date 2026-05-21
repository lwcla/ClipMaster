package com.cla.clip.base.general.backup

import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证恢复时钟归一化只修正未来时间，不改变正常备份时间轴。 */
class BackupRestoreTimeNormalizerTest {
    /** 远端设备时钟偏快时，恢复时间会整体平移到当前设备时间轴，并保留相对间隔。 */
    @Test
    fun normalizeUserTimeShiftsFutureBackupTimesToLocalTimeline() {
        val normalizer = BackupRestoreTimeNormalizer(
            restoreStartedAt = 1_000L,
            manifestCreatedAt = 1_600L
        )

        assertEquals(900L, normalizer.normalizeUserTime(1_500L))
        assertEquals(990L, normalizer.normalizeUserTime(1_590L))
        assertEquals(1_000L, normalizer.normalizedManifestCreatedAt)
        assertEquals(2, normalizer.normalizedFieldCount)
        assertEquals(600L, normalizer.clockSkewMillis)
    }

    /** 未设置的状态时间必须继续保持 0，避免把未置顶、未折叠或未删除误判为当前时间。 */
    @Test
    fun normalizeUserTimeKeepsUnsetStateTime() {
        val normalizer = BackupRestoreTimeNormalizer(
            restoreStartedAt = 1_000L,
            manifestCreatedAt = 1_600L
        )

        assertEquals(0L, normalizer.normalizeUserTime(0L))
        assertEquals(0, normalizer.normalizedFieldCount)
    }

    /** 正常备份创建时间不晚于当前设备时，不应改变历史时间。 */
    @Test
    fun normalizeUserTimeKeepsPastBackupTimes() {
        val normalizer = BackupRestoreTimeNormalizer(
            restoreStartedAt = 1_000L,
            manifestCreatedAt = 900L
        )

        assertEquals(800L, normalizer.normalizeUserTime(800L))
        assertEquals(900L, normalizer.normalizedManifestCreatedAt)
        assertEquals(0L, normalizer.clockSkewMillis)
        assertEquals(0, normalizer.normalizedFieldCount)
    }
}
