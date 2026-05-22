package com.cla.clip.master.ui.page.backup

import android.net.Uri
import com.cla.clip.base.general.backup.RemoteBackupFile
import com.cla.clip.base.general.utils.logD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 备份恢复 feature 内部的一次性恢复请求流。
 *
 * 只用于 `BackupVm` 向 `BackupRestoreVm` 交付待打开的备份目标；目标页接管后按 requestId 清空。
 * 这里使用单槽 StateFlow，是为了保证恢复页 ViewModel 创建晚于导航时仍能拿到最近一次请求。
 */
@Singleton
class BackupRestoreRequests @Inject constructor() {
    companion object {
        private const val TAG = "BackupRestoreRequests"
    }

    private val _requests = MutableStateFlow<BackupRestoreRequest?>(null)

    val requests: StateFlow<BackupRestoreRequest?> = _requests.asStateFlow()

    /** 写入新的备份恢复请求；旧请求会被覆盖，避免连续点击后恢复页消费过期目标。 */
    fun set(request: BackupRestoreRequest) {
        _requests.value = request
        logD(TAG) { "备份恢复请求已写入 requestId=${request.requestId} sourceType=${request.sourceLogCode}" }
    }

    /** 恢复页接管请求后清空单槽，避免页面重建时重复消费。 */
    fun clear(requestId: Long) {
        _requests.update { current ->
            if (current?.requestId == requestId) {
                logD(TAG) { "备份恢复请求已清理 requestId=${current.requestId} sourceType=${current.sourceLogCode}" }
                null
            } else {
                current
            }
        }
    }
}

/** 备份恢复入口请求，只描述“从哪里打开哪个备份”，真正读取、下载和恢复由恢复页 ViewModel 执行。 */
sealed class BackupRestoreRequest(
    /** 请求 id 用于恢复页幂等消费，避免重组或页面重建时重复启动读取任务。 */
    val requestId: Long = System.nanoTime(),
) {
    /** 从系统文件选择器选择的 zip 备份。 */
    class LocalFile(val uri: Uri) : BackupRestoreRequest()

    /** 从本地备份目录列表选择的备份。 */
    class LocalDirectory(val file: LocalBackupFile) : BackupRestoreRequest()

    /** 从 WebDAV 备份列表选择的备份。 */
    class WebDav(val file: RemoteBackupFile) : BackupRestoreRequest()
}

/** 恢复请求低敏日志值，禁止输出 URI、WebDAV 地址、远端路径或文件内容。 */
private val BackupRestoreRequest.sourceLogCode: String
    get() = when (this) {
        is BackupRestoreRequest.LocalFile -> "local_file"
        is BackupRestoreRequest.LocalDirectory -> "local_directory"
        is BackupRestoreRequest.WebDav -> "webdav"
    }
