package com.cla.clip.master.logging

import org.junit.Assert.*
import org.junit.Test

/** 合成路径与命令保护大小/数量政策，禁止真实日志或设备路径进入 fixture。 */
class LogCapturePolicyTest {
    /** 直接输出必须使用父应用 PID、10 MiB 与 20 历史，不引入清理或 shell。 */
    @Test fun nativeRotationUsesConfirmedPolicy() {
        /** 合成日志目标只用于参数检查，不创建文件。 */
        val command = LogCapturePolicy.command(123, "/synthetic/Download/ClipMaster/logs/clipmaster.txt", "10-13 14:00:00.000")
        assertEquals("logcat", command.first())
        assertTrue(command.contains("--pid=123"))
        assertEquals("10240", command[command.indexOf("-r") + 1])
        assertEquals("20", command[command.indexOf("-n") + 1])
        assertTrue(command.contains("*:V"))
        assertFalse(command.contains("-c"))
        assertFalse(command.any { it.startsWith("--id") })
    }

    /** 统计日志可包括旧会话，管理轮转则只认固定文件族，不能误删其他记录。 */
    @Test fun managedFilesAndVisibleLegacyLogsHaveDifferentScopes() {
        assertTrue(LogCapturePolicy.isManagedFile("clipmaster.txt"))
        assertTrue(LogCapturePolicy.isManagedFile("clipmaster.txt.01"))
        assertTrue(LogCapturePolicy.isManagedFile("clipmaster.txt.20"))
        assertFalse(LogCapturePolicy.isManagedFile("clipmaster.txt.21"))
        assertFalse(LogCapturePolicy.isManagedFile("clipmaster.txt.1"))
        assertFalse(LogCapturePolicy.isManagedFile("../clipmaster.txt"))
        assertTrue(LogCapturePolicy.isLogFile("clipmaster-synthetic-0001.txt"))
        assertFalse(LogCapturePolicy.isLogFile("notes.txt"))
    }

    /** 非法 PID 或非固定文件目标在进程启动前拒绝。 */
    @Test fun rejectsInvalidCommandIdentity() {
        assertThrows(IllegalArgumentException::class.java) { LogCapturePolicy.command(0, "/synthetic/clipmaster.txt", "10-13 14:00:00.000") }
        assertThrows(IllegalArgumentException::class.java) { LogCapturePolicy.command(123, "/synthetic/other.txt", "10-13 14:00:00.000") }
    }

    /** stderr 只返回固定分类，不复述外部地址或原始命令错误。 */
    @Test fun commandFailuresKeepOnlyStableCodes() {
        assertEquals("log_rotation_failed", LogCapturePolicy.stderrReason("rename('synthetic-secret', 'target') failed"))
        assertEquals("log_output_failed", LogCapturePolicy.stderrReason("couldn't open output file 'synthetic-secret'"))
        assertEquals("log_command_failed", LogCapturePolicy.stderrReason("synthetic-secret"))
    }
}
