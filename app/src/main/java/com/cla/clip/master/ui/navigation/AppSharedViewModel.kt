package com.cla.clip.master.ui.navigation

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.cla.clip.base.general.backup.RemoteBackupFile
import com.cla.clip.base.general.utils.logD
import com.cla.clip.master.ui.page.backup.LocalBackupFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Activity 级页面间临时数据容器。
 *
 * 只保存跨导航页面必须传递的短生命周期请求，不承载页面表单、列表或业务流程状态；目标页面接管请求后必须清理。
 */
class AppSharedViewModel : ViewModel() {
    companion object {
        /** 日志标签，只记录请求类型和 requestId，不输出 URI、远端路径或文件内容。 */
        private const val TAG = "AppSharedViewModel"
    }

    private val _backupRestoreRequest = MutableStateFlow<BackupRestoreRequest?>(null)

    /** 备份页发起、恢复页消费的一次性恢复请求。 */
    val backupRestoreRequest = _backupRestoreRequest.asStateFlow()

    /** 写入新的备份恢复请求；旧请求会被覆盖，避免用户连续点击多个备份后恢复页拿到过期目标。 */
    fun setBackupRestoreRequest(request: BackupRestoreRequest) {
        _backupRestoreRequest.value = request
        logD(TAG) { "备份恢复临时请求已写入 requestId=${request.requestId} sourceType=${request.sourceLogCode}" }
    }

    /** 恢复页接管请求或关闭时清理，避免 Activity 级 ViewModel 长期持有 URI 或列表对象。 */
    fun clearBackupRestoreRequest(requestId: Long? = null) {
        _backupRestoreRequest.update { current ->
            if (requestId == null || current?.requestId == requestId) {
                if (current != null) {
                    logD(TAG) { "备份恢复临时请求已清理 requestId=${current.requestId} sourceType=${current.sourceLogCode}" }
                }
                null
            } else {
                current
            }
        }
    }
}

/** 备份恢复入口请求，只描述“从哪里打开哪个备份”，真正读取、下载和恢复由恢复页 ViewModel 执行。 */
sealed class BackupRestoreRequest(
    /** 请求 id 用于恢复页幂等消费，避免重组时重复启动读取任务。 */
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
