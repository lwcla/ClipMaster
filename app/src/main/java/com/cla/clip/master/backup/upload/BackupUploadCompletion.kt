package com.cla.clip.master.backup.upload

import com.cla.clip.base.general.backup.settings.BackupPendingUpload
import com.cla.clip.master.backup.BackupResultPersistence

/** 先提交上传终态再释放私有包；调用方必须持有备份任务锁，避免检查与清理之间换包。 */
internal class BackupUploadCompletion(
    /** 读取当前文件身份，不能把读取失败解释为没有文件。 */
    private val read: suspend () -> BackupPendingUpload?,
    /** 同一事务提交分目标结果和终态；身份不匹配返回 false，存储错误向上传播。 */
    private val commit: suspend (String, Long, String) -> Boolean,
    /** 删除指定身份文件；失败时保留已提交终态供下次仅做清理。 */
    private val remove: (String) -> Unit,
    /** 删除文件成功后才能释放当前指针。 */
    private val clear: suspend (String) -> Unit,
) {
    /** 提交失败或旧身份不得删除文件，成功提交后执行终态清理。 */
    suspend fun finish(artifactId: String, completedAt: Long, reasonCode: String = "") {
        /** 成功结果写盘失败不能被外层当作上传失败再次提交。 */
        val committed = BackupResultPersistence.commit(publicationSucceeded = reasonCode.isBlank()) {
            commit(artifactId, completedAt, reasonCode)
        }
        if (committed) cleanup(artifactId)
    }

    /** 只清理匹配的终态包，保留等待包以及替换后的新身份。 */
    suspend fun cleanup(artifactId: String) {
        /** 再次核对终态及文件所有权，不依赖调用时的旧快照。 */
        val current = read()
        if (current?.artifactId != artifactId || !current.terminal) return
        remove(artifactId)
        clear(artifactId)
    }
}
