package com.cla.clip.master.logging

/** 只构造固定采集参数和文件身份，不执行 shell、IO 或改变业务日志。 */
internal object LogCapturePolicy {
    /** 公共排障目录，不纳入用户备份。 */
    const val relativePath = "Download/ClipMaster/logs/"
    /** 原生轮转的基础文件，仅同族编号文件参与淘汰。 */
    const val fileName = "clipmaster.txt"
    /** 单卷阈值使用 logcat 的 KiB 单位，即 10 MiB。 */
    const val rotateKiB = 10240
    /** 20 个历史之外还有当前文件，最大正常集合为 21 份。 */
    const val historyCount = 20

    /** 参数列表拒绝任意 PID/文件名；时间由应用本次开启生成，不回读无限历史。 */
    fun command(pid: Int, outputPath: String, since: String): List<String> {
        require(pid > 0 && outputPath.endsWith("/$fileName"))
        require(since.matches(Regex("[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}")))
        return listOf("logcat", "--pid=$pid", "-b", "main", "-b", "system", "-b", "crash",
            "-v", "threadtime,year", "-T", since, "-f", outputPath,
            "-r", rotateKiB.toString(), "-n", historyCount.toString(), "*:V")
    }

    /** 固定编号必须为 01–20，不把其他名字授权给轮转或删除。 */
    fun isManagedFile(name: String): Boolean = name == fileName ||
        (1..historyCount).any { name == "$fileName.${it.toString().padStart(2, '0')}" }

    /** 数量展示包含可识别的旧会话，既有旧文件不受新轮转删除授权影响。 */
    fun isLogFile(name: String): Boolean = isManagedFile(name) ||
        name.matches(Regex("clipmaster-[A-Za-z0-9-]+-[0-9]{4}\\.txt"))

    /** 外部 stderr 只用于匹配固定原因，返回值不包含原文或文件路径。 */
    fun stderrReason(line: String): String = when {
        line.contains("rename(") || line.contains("rotating log") -> "log_rotation_failed"
        line.contains("output file") || line.contains("Output error") -> "log_output_failed"
        else -> "log_command_failed"
    }
}
