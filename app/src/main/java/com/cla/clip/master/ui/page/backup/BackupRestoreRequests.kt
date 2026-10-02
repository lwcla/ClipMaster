package com.cla.clip.master.ui.page.backup

import android.net.Uri
import com.cla.clip.master.backup.local.LocalBackupFile
import com.cla.clip.base.general.backup.RemoteBackupFile

/** 备份恢复入口请求，只描述“从哪里打开哪个备份”，真正读取、下载和恢复由恢复页 ViewModel 执行。 */
sealed class BackupRestoreRequest(
    /** 请求 id 用于恢复页幂等消费，避免重组或页面重建时重复启动读取任务。 */
    val requestId: Long = System.nanoTime(),
) {
    /** 从系统文件选择器选择的 zip 备份。 */
    class LocalFile(/** 系统选择器授权仅当前会话使用，不持久化。 */ val uri: Uri) : BackupRestoreRequest()

    /** 从本地备份目录列表选择的备份。 */
    class LocalDirectory(/** 用户选中的目录备份，仅透传给预检。 */ val file: LocalBackupFile) : BackupRestoreRequest()

    /** 从 WebDAV 备份列表选择的备份。 */
    class WebDav(/** 用户选中的远端条目，不通过路由传输路径。 */ val file: RemoteBackupFile) : BackupRestoreRequest()
}

/** 恢复请求低敏日志值，禁止输出 URI、WebDAV 地址、远端路径或文件内容。 */
internal val BackupRestoreRequest.sourceLogCode: String
    get() = when (this) {
        is BackupRestoreRequest.LocalFile -> "local_file"
        is BackupRestoreRequest.LocalDirectory -> "local_directory"
        is BackupRestoreRequest.WebDav -> "webdav"
    }
