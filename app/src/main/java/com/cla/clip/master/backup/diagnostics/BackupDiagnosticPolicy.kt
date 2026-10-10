package com.cla.clip.master.backup.diagnostics

/** 当前任务的低敏即时诊断事件，手工格式化，不保存文件，也不参与反射、备份或外部协议。 */
internal data class BackupDiagnosticRecord(
    /** 墙钟时间只用于对照系统日志，单位毫秒。 */
    val at: Long,
    /** 阶段固定 code，不能传入错误原文。 */
    val phase: String,
    /** 事件结论与阶段分开，Worker 成功不是远端成功。 */
    val event: String = "phase",
    /** WorkManager UUID，不携带输入或输出数据。 */
    val workId: String = "",
    /** 本机生成的产物身份，不是文件路径。 */
    val artifactId: String = "",
    /** auto/manual 固定来源。 */
    val source: String = "",
    /** 从执行开始使用单调时钟计算的毫秒跨度。 */
    val elapsedMs: Long = 0,
    /** 当前执行次数，不当作重试已经成功。 */
    val attempt: Int = 0,
    /** WorkManager 的原始低敏停止原因数值。 */
    val stopReason: Int = -256,
    /** 只允许异常类型名称，禁止异常 message。 */
    val errorType: String = "",
    /** 稳定失败 code，不依赖 R8 后的异常类名或后来被覆盖的结果。 */
    val reasonCode: String = "",
)

/** 即时诊断字段白名单，不依赖 Android 或文件存储；非法自由文本整体脱敏。 */
internal object BackupDiagnosticPolicy {
    /** 身份和 code 只接受有限长度的 ASCII 单词，禁止路径、URL、换行与自由文本。 */
    private val safeToken = Regex("[A-Za-z0-9_.-]{0,96}")
    /** 非法 token 整体移除，不能只截断可能包含敏感内容的前缀。 */
    private fun token(value: String): String = if (safeToken.matches(value) && !value.contains("..")) value else "redacted"
    /** 生成一行低敏事件；非法自由文本必须整体脱敏。 */
    fun encode(record: BackupDiagnosticRecord): String =
        "at=${record.at.coerceAtLeast(0)} event=${token(record.event)} phase=${token(record.phase)} " +
            "workId=${token(record.workId)} artifactId=${token(record.artifactId)} source=${token(record.source)} " +
            "elapsedMs=${record.elapsedMs.coerceAtLeast(0)} attempt=${record.attempt.coerceAtLeast(0)} " +
            "stopReason=${record.stopReason} errorType=${token(record.errorType)} reasonCode=${token(record.reasonCode)}"
}
