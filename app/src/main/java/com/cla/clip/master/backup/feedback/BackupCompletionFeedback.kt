package com.cla.clip.master.backup.feedback

import com.cla.clip.base.general.backup.settings.BackupResultTarget
import com.cla.clip.master.backup.BackupResultPersistence
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 结果提交与提示的纯边界；提示依赖由调用方替换，不依赖 Android 或页面生命周期。 */
internal class BackupCompletionFeedback(
    /** 只接收真实发布结论的展示副作用，不接收异常正文或配置。 */
    private val show: suspend (Boolean) -> Unit,
    /** 提示异常的低敏诊断，不参与任务结果或重试。 */
    private val onShowFailure: (Throwable) -> Unit = {},
) {
    /** 事务接受的手动云端结果才提示；展示及诊断异常隔离，保存失败仍传播既有契约。 */
    suspend fun complete(source: String, target: BackupResultTarget, publicationSucceeded: Boolean,
        record: suspend () -> Boolean): Boolean {
        /** 条件提交返回值同时排除旧任务、重复终态及无真实保存的完成提示。 */
        val recorded = BackupResultPersistence.commit(publicationSucceeded, record)
        if (recorded && source == "manual" && target == BackupResultTarget.WebDav) {
            // 已保存结果后的短提示不受原 Worker 取消影响，且不得传播成上传失败。
            withContext(NonCancellable) {
                try { show(publicationSucceeded) }
                catch (failure: Throwable) {
                    // failure 只供低敏诊断；连诊断失败也不能反转结果或触发第二次保存。
                    runCatching { onShowFailure(failure) }
                }
            }
        }
        return recorded
    }
}
