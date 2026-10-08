package com.cla.clip.master.logging

/** 固定应用日志采集范围与文件命名，不允许任意 shell 或目录输入。 */
internal object AppLogRecordingPolicy {
    /** 公共日志目录，不纳入应用备份。 */
    const val relativePath = "Download/ClipMaster/logs/"
    /** 分卷阈值只限制单卷，不自动删除历史日志。 */
    const val maxFileBytes = 10L * 1024 * 1024
    /** 会话名由时间、PID、UUID 构成，拒绝目录穿越和自由文本。 */
    private val safeSession = Regex("[A-Za-z0-9-]{1,96}")
    /** 直接参数列表交给 ProcessBuilder，不使用 shell。 */
    fun command(pid: Int, since: String): List<String> {
        require(pid > 0)
        return listOf("logcat", "--pid=$pid", "-b", "main", "-b", "system", "-b", "crash",
            "-v", "threadtime,year", "-T", since, "*:V")
    }
    /** 会话身份只能来自应用的随机身份与时间。 */
    fun fileName(session: String, index: Int): String {
        require(safeSession.matches(session) && index >= 0)
        return "clipmaster-$session-${index.toString().padStart(4, '0')}.txt"
    }
}
