package com.cla.clip.master.backup.upload

import com.cla.clip.base.general.backup.settings.BackupPendingUpload
import com.cla.clip.master.backup.BackupSchedulingPolicy

/** 调用方持有任务锁；先交接新引用和入队，成功后才释放旧包。 */
internal class BackupPendingHandoff(
    /** 原子替换当前身份，失败不能改变已提交旧身份。 */
    private val replace: suspend (BackupPendingUpload) -> Unit,
    /** 入队失败由队列协作者保存新包失败终态。 */
    private val enqueue: suspend (BackupPendingUpload) -> Unit,
    /** 新任务已登记后释放旧请求/文件；维护错误由调用方记录，不反转新包结论。 */
    private val release: suspend (BackupPendingUpload) -> Unit,
    /** 回滚引用后丢弃失败新包，旧包仍持有文件所有权。 */
    private val discard: suspend (BackupPendingUpload) -> Unit,
) {
    /** 明确入队失败恢复仍可等待的旧身份，不恢复任何已经终结或上传中断的旧包。 */
    suspend fun accept(fresh: BackupPendingUpload, previous: BackupPendingUpload?) {
        replace(fresh)
        try { enqueue(fresh) } catch (failure: Throwable) {
            if (previous != null && BackupSchedulingPolicy.canSchedule(previous.phase)) replace(previous)
            discard(fresh)
            throw failure
        }
        if (previous != null) release(previous)
    }
}
