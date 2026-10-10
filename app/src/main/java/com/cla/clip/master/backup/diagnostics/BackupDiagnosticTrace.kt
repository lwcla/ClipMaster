package com.cla.clip.master.backup.diagnostics

/** 一轮 Worker/手动请求的低敏上下文；同一事件可串联执行、任务锁等待和产物身份。 */
internal class BackupDiagnosticTrace(
    /** 只提交白名单事件，由适配器即时输出 Logcat，不持久化阶段历史。 */
    private val record: (BackupDiagnosticRecord) -> Unit,
    /** 可注入单调时钟，避免用排队墙钟时间误判执行超时。 */
    private val elapsedClock: () -> Long,
    /** 墙钟仅用于对照系统日志。 */
    private val wallClock: () -> Long,
    /** 当前 Worker UUID，手动生成时为空。 */
    private val workId: String,
    /** 当前已确认来源；上传读取 pending 后补齐。 */
    private var source: String,
    /** 当前实际运行次数，不来自业务输入。 */
    private val attempt: Int,
    /** 产物身份，生成后绑定；不持有路径、配置或凭据。 */
    private var artifactId: String,
) {
    /** 每一轮执行单独起算，包含任务锁等待，不包含尚未运行的排队时间。 */
    private val startedAt = elapsedClock()
    /** 最近进入的阶段；结束事件保留它，不能被笼统错误覆盖。 */
    private var phase = "worker_entry"

    /** 绑定生成/上传的身份与来源，不输出其他 pending 字段。 */
    fun bind(artifactId: String, source: String) {
        this.artifactId = artifactId
        this.source = source
        event("identity_bound")
    }

    /** 在副作用开始前即时输出阶段；不承诺硬杀或重启后仍能从日志缓冲追溯。 */
    fun stage(phase: String) {
        this.phase = phase
        event("phase")
    }

    /** 结束/停止事件保持阶段和身份；不把 Worker success 解释为远端发布成功。 */
    fun event(event: String, stopReason: Int = -256, errorType: String = "", reasonCode: String = "") {
        record(BackupDiagnosticRecord(wallClock(), phase, event, workId, artifactId, source,
            (elapsedClock() - startedAt).coerceAtLeast(0), attempt, stopReason, errorType, reasonCode))
    }
}
