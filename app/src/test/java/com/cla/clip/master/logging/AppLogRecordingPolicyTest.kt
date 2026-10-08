package com.cla.clip.master.logging

import org.junit.Assert.*
import org.junit.Test

/** 日志采集只限本应用 PID，文件命名不能成为任意路径入口。 */
class AppLogRecordingPolicyTest {
    /** 同时覆盖原生模块日志及统一入口各级日志，不按 TAG 漏筛。 */
    @Test fun commandCapturesOwnProcessAndEveryLevel() {
        /** 时间与 PID 均为合成值。 */
        val command = AppLogRecordingPolicy.command(123, "10-08 00:00:00.000")
        assertEquals(listOf("logcat", "--pid=123", "-b", "main", "-b", "system", "-b", "crash", "-v", "threadtime,year", "-T", "10-08 00:00:00.000", "*:V"), command)
        assertThrows(IllegalArgumentException::class.java) { AppLogRecordingPolicy.command(0, "synthetic") }
    }

    /** 固定目录及合法会话名防止覆写用户其他文件。 */
    @Test fun fileNameRejectsPathsAndNegativeSegments() {
        assertEquals("clipmaster-session-123-0000.txt", AppLogRecordingPolicy.fileName("session-123", 0))
        assertEquals("Download/ClipMaster/logs/", AppLogRecordingPolicy.relativePath)
        assertThrows(IllegalArgumentException::class.java) { AppLogRecordingPolicy.fileName("../private", 0) }
        assertThrows(IllegalArgumentException::class.java) { AppLogRecordingPolicy.fileName("session", -1) }
    }
}
