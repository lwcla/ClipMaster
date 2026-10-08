package com.cla.clip.base.general.backup

import android.content.Context
import android.os.StatFs
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 备份临时文件目录管理器。
 *
 * 流式备份会产生导出中间 JSONL、最终 zip 和 WebDAV 下载临时包；这些文件不属于用户正式备份，
 * 必须集中放在应用私有缓存目录并按 taskId 标记，避免失败或取消后长期占用空间。
 */
@Singleton
class BackupTempFileStore internal constructor(
    /** 私有缓存备份根目录；测试只注入合成目录，不持有页面上下文。 */
    private val cacheRoot: File,
) {
    /** Android 装配仍只选择既有 backup_tmp 域。 */
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.cacheDir, ROOT_DIR))
    companion object {
        /** 日志标签，只记录任务 id、目录类型和数量，不输出绝对路径。 */
        private const val TAG = "BackupTempFileStore"

        /** 备份临时根目录。 */
        private const val ROOT_DIR = "backup_tmp"

        /** 过期临时文件保留时长，避免运行中任务被启动清理误删。 */
        private const val MAX_TEMP_AGE_MS = 24L * 60L * 60L * 1000L
    }

    /** 创建导出任务临时目录，目录名包含 taskId，方便失败后按任务清理。 */
    fun createExportDir(taskId: String): File = createTaskDir("export", taskId)

    /** 创建外部导入或 WebDAV 下载临时目录。 */
    fun createImportDir(taskId: String): File = createTaskDir("import", taskId)

    /** 下一轮开始清理后台生成半包，不能触碰手动文件导出或恢复预览。 */
    fun cleanupBackgroundExports() {
        /** 仅允许 backup_tmp/export 直属范围，拒绝指向范围外的软链接。 */
        val exportRoot = File(rootDir(), "export").canonicalFile
        require(exportRoot.parentFile == rootDir().canonicalFile) { "export_outside_root" }
        /** 后台 UUID 和旧 daily/manual-webdav 身份；manual-local 与 import 明确排除。 */
        val backgroundIdentity = Regex("(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|(?:daily|manual-webdav)-[A-Za-z0-9_-]+)")
        exportRoot.listFiles().orEmpty().filter { backgroundIdentity.matches(it.name) }.forEach { child ->
            require(child.canonicalFile.parentFile == exportRoot) { "export_outside_root" }
            check(child.deleteRecursively()) { "export_cleanup_failed" }
        }
    }

    /** 检查私有缓存目录剩余空间；不足时提前失败，避免写出半截备份。 */
    fun ensureAvailableSpace(requiredBytes: Long) {
        val statFs = StatFs(rootDir().absolutePath)
        val available = statFs.availableBytes
        if (available < requiredBytes) {
            throw BackupFailure.InsufficientSpace()
        }
    }

    /** 删除单个任务目录；失败只记录日志，避免清理异常覆盖真实业务失败。 */
    fun cleanupTaskDir(dir: File?, taskId: String? = null) {
        if (dir == null || !dir.exists()) return
        runCatching {
            dir.deleteRecursively()
        }.onFailure { throwable ->
            logE(TAG) {
                "备份临时目录清理失败 ${backupTaskLogField(taskId)}reasonCode=${throwable.backupReasonCode()} type=${throwable::class.simpleName}"
            }
        }
    }

    /** 清理过期临时文件；运行中任务目录由于 mtime 很新，不会被启动清理误删。 */
    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val root = rootDir()
        if (!root.exists()) return
        var deleted = 0
        root.listFiles().orEmpty().forEach { group ->
            group.listFiles().orEmpty().forEach { taskDir ->
                if (now - taskDir.lastModified() > MAX_TEMP_AGE_MS && taskDir.deleteRecursively()) {
                    deleted += 1
                }
            }
        }
        if (deleted > 0) {
            logD(TAG) { "过期备份临时目录清理完成 deleted=$deleted" }
        }
    }

    /** 创建指定类型的任务目录。 */
    private fun createTaskDir(type: String, taskId: String): File {
        val dir = File(File(rootDir(), type), sanitizeTaskId(taskId)).apply {
            if (!exists() && !mkdirs()) throw BackupFailure.StorageNotWritable()
        }
        return dir
    }

    /** 返回备份临时根目录。 */
    private fun rootDir(): File {
        return cacheRoot.apply {
            if (!exists()) mkdirs()
        }
    }

    /** 清理 taskId 中不适合文件名的字符，避免未来 taskId 变更影响目录创建。 */
    private fun sanitizeTaskId(taskId: String): String {
        return taskId.replace(Regex("[^A-Za-z0-9_-]"), "_")
    }
}
