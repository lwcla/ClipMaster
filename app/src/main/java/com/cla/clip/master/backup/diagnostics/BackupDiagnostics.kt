package com.cla.clip.master.backup.diagnostics

import android.content.Context
import android.os.SystemClock
import android.os.Build
import android.util.AtomicFile
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import com.cla.clip.base.general.utils.logE
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 仅用于备份的安装内持久化诊断，不依赖正式包的 Logcat 开关，不访问业务数据。 */
@Singleton
internal class BackupDiagnostics @Inject constructor(
    /** 诊断必须位于 noBackupFilesDir，不能进入用户备份或系统迁移。 */
    @ApplicationContext context: Context,
) {
    /** 单个固定日志文件，不接受用户路径；AtomicFile 保护写入中断时的旧历史。 */
    private val file = AtomicFile(File(context.noBackupFilesDir, "backup_diagnostics/events.log"))
    /** 纯留证规则与 Android 原子写入分离，IO 失败不会进入业务异常分支。 */
    private val journal = BackupDiagnosticJournal(
        read = {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) ""
            else file.openRead().use { input ->
                // 固定上限读取，损坏的超大文件不能占用无界内存。
                input.readBytesBounded()
            }
        },
        write = { text ->
            check(file.baseFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
            /** 原子临时输出，只在完整写入和同步后替换正式诊断文件。 */
            val output = file.startWrite()
            try {
                output.write(text.toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (failure: Exception) {
                file.failWrite(output)
                throw failure
            }
        },
    )

    /** 创建一轮执行记录；不读取设置、任务队列或业务数据库。 */
    fun start(workId: String = "", source: String, attempt: Int = 0, artifactId: String = ""): BackupDiagnosticTrace =
        BackupDiagnosticTrace(::record, SystemClock::elapsedRealtime, System::currentTimeMillis,
            workId, source, attempt, artifactId).also { it.event("started") }

    /** Android 12 起读取当次停止证据，旧平台明确未知，不从执行时长推算原因。 */
    fun stopReason(worker: ListenableWorker): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) worker.stopReason else WorkInfo.STOP_REASON_UNKNOWN

    /** 同步的低频有界 IO，调用方使用后台线程；诊断失败仅打印固定警告。 */
    fun record(record: BackupDiagnosticRecord) {
        if (!journal.record(record)) logW("BackupDiagnostics") { "诊断记录未保存 reasonCode=diagnostic_store_failed" }
        when (record.event) {
            "stopped", "interrupted" -> logW("BackupDiagnostics") { "备份中断 ${BackupDiagnosticPolicy.encode(record)}" }
            "worker_failed", "generation_failed", "upload_failed" -> logE("BackupDiagnostics") { "备份异常 ${BackupDiagnosticPolicy.encode(record)}" }
            else -> logI("BackupDiagnostics") { "备份阶段 ${BackupDiagnosticPolicy.encode(record)}" }
        }
    }

    /** 读取最多固定字节；额外历史不会跟随备份数据量增长。 */
    private fun java.io.InputStream.readBytesBounded(): String {
        /** 文件的有限内存缓冲，不会读取备份包或配置。 */
        val bytes = ByteArray(BackupDiagnosticPolicy.maxBytes)
        /** 已读取字节数，读取达到上限后即停止。 */
        var size = 0
        while (size < bytes.size) {
            /** 本轮读取数量，负值表示文件结束。 */
            val count = read(bytes, size, bytes.size - size)
            if (count <= 0) break
            size += count
        }
        return String(bytes, 0, size, Charsets.UTF_8)
    }
}
