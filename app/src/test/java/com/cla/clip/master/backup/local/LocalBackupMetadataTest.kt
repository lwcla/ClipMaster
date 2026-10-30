package com.cla.clip.master.backup.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/** 保护单 ZIP 列表的时间、稳定顺序与删除资格；不读取 Android URI 或真实备份。 */
class LocalBackupMetadataTest {
    /** 文件名创建时间必须优先于复制文件时更新的修改时间。 */
    @Test fun filenameTimeWinsOverModificationTime() {
        /** 以生产协议相同的默认时区构造独立期望值，避免测试依赖运行机器时区。 */
        val expected = GregorianCalendar(TimeZone.getDefault()).apply {
            clear()
            set(2026, Calendar.OCTOBER, 30, 12, 0, 0)
        }.timeInMillis
        assertEquals(expected, metadata("clip_master_backup_test_20261030_120000.zip", 99L).sortCreatedAt)
    }

    /** 无文件名时间时使用修改时间，但未知文件仍不能被保留清理删除。 */
    @Test fun unknownNameUsesModificationTimeWithoutBecomingDeletable() {
        /** 非标准名字即使修改时间已知也没有普通备份身份。 */
        val file = metadata("clip_master_backup_unknown.zip", 42L)
        assertEquals(42L, file.sortCreatedAt)
        assertFalse(file.isRegularBackup)
        assertEquals(0L, metadata("clip_master_backup_unknown.zip").sortCreatedAt)
    }

    /** 非法日期不能通过正则形状被误当普通备份。 */
    @Test fun invalidDateIsNotDeletable() {
        /** 二月不存在三十日；修改时间只供展示。 */
        val file = metadata("clip_master_backup_test_20260230_120000.zip", 42L)
        assertEquals(42L, file.sortCreatedAt)
        assertFalse(file.isRegularBackup)
    }

    /** 当前普通文件、旧手动文件均无需 sidecar 即可成为保留候选。 */
    @Test fun normalArchivesAreDeletableWithoutManifest() {
        assertTrue(metadata("clip_master_backup_round-a_20261030_120000.zip").isRegularBackup)
        assertTrue(metadata("clip_master_backup_install-test_20260519_213045.zip").isRegularBackup)
    }

    /** 历史回滚点保留原识别能力，不能被普通 N 份策略删除。 */
    @Test fun safetyArchiveIsNotDeletable() {
        /** 旧协议安全快照的标准名字。 */
        val file = metadata("clip_master_backup_install-test_safety_20260519_213045.zip")
        assertTrue(file.isSafetySnapshot)
        assertFalse(file.isRegularBackup)
    }

    /** 临时文件、摘要、外部 ZIP 和非 ZIP 文件不能参与列表或删除。 */
    @Test fun unrelatedFilesAndTemporaryArtifactsAreExcluded() {
        // 每个名称分别覆盖不属于正式备份的副产物或外部文件。
        for (name in listOf(
            "clip_master_backup_test_20261030_120000.zip.tmp",
            "clip_master_backup_test_20261030_120000.zip.manifest.json",
            "other_20261030_120000.zip",
            "clip_master_backup_test_20261030_120000.json",
        )) {
            assertFalse(name, metadata(name).isBackupArchive)
            assertFalse(name, metadata(name).isRegularBackup)
        }
    }

    /** 排序按创建时间降序，同秒再按修改时间、文件名降序，结果与目录枚举顺序无关。 */
    @Test fun newestFirstOrderHasStableTieBreakers() {
        /** 枚举故意打乱，并让旧文件修改时间更晚，保护创建时间优先规则。 */
        val files = listOf(
            metadata("clip_master_backup_a_20261030_120000.zip", 1L),
            metadata("clip_master_backup_old_20261029_120000.zip", Long.MAX_VALUE),
            metadata("clip_master_backup_c_20261030_120000.zip", 2L),
            metadata("clip_master_backup_b_20261030_120000.zip", 2L),
        )
        assertEquals(listOf(files[2], files[3], files[0], files[1]), files.sorted())
    }

    /** 未知时间的目录条目使用修改时间排序；不依赖 sidecar 精度。 */
    @Test fun fallbackTimesParticipateInOrdering() {
        /** 缺失时间、已知修改时间两条未知名称条目。 */
        val files = listOf(metadata("clip_master_backup_a.zip"), metadata("clip_master_backup_b.zip", 42L))
        assertEquals(listOf(files[1], files[0]), files.sorted())
    }

    /** 只构造目录可查询元数据，测试无外部 JSON 输入。 */
    private fun metadata(name: String, modified: Long? = null): LocalBackupMetadata =
        LocalBackupMetadata(fileName = name, size = 10L, lastModified = modified)
}
