package com.cla.clip.master.backup.diagnostics

/** 文件读写失败只影响诊断，绝不传播到备份业务结果。 */
internal class BackupDiagnosticJournal(
    /** 由平台适配器提供有界读取，不接触业务数据库。 */
    private val read: () -> String,
    /** 平台适配器负责原子替换诊断文件。 */
    private val write: (String) -> Unit,
) {
    /** 多个 Worker 共享同一日志，锁只保护有界诊断 IO，不持有备份业务锁。 */
    private val lock = Any()
    /** 返回是否保存，失败不得吞掉或伪造备份结论。 */
    fun record(record: BackupDiagnosticRecord): Boolean = synchronized(lock) {
        try {
            write(BackupDiagnosticPolicy.append(read(), BackupDiagnosticPolicy.encode(record)))
            true
        } catch (_: Exception) {
            // 不输出异常原文；诊断损坏或写盘失败不能变成业务备份失败。
            false
        }
    }
}
