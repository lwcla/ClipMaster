package com.cla.clip.master.backup.feedback

import android.content.Context
import com.cla.clip.base.general.backup.settings.BackupResultTarget
import com.cla.clip.base.general.utils.logW
import com.cla.clip.base.general.utils.toast
import com.cla.clip.master.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 应用级手动云端结果反馈，不持有页面；Toast 最好努力展示，持久化结果仍是用户查询依据。 */
@Singleton
internal class ManualBackupCompletionNotifier @Inject constructor(
    /** 跨页面提示只使用应用上下文，不保留 Activity、凭据或备份内容。 */
    @param:ApplicationContext private val context: Context,
) {
    /** 纯规则持有两个小型平台回调，不观察历史结果或创建额外后台任务。 */
    private val feedback = BackupCompletionFeedback(
        // succeeded 是已发布并保存的结论；只向现有 Toast 工具传固定、非敏感资源文案。
        show = { succeeded ->
            /** 成功与失败使用短文案，具体原因由备份页面已有摘要展示。 */
            val resource = if (succeeded) R.string.host_backup_manual_webdav_success
                else R.string.host_backup_manual_webdav_failed
            context.toast(resource)
        },
        // failure 只记录提示阶段的异常类型，不打印异常正文或声称用户已看到 Toast。
        onShowFailure = { failure ->
            logW("ManualBackupCompletionNotifier") { "手动云端备份提示失败 type=${failure.javaClass.simpleName}" }
        },
    )

    /** 保存本轮真实结论并按来源提示，条件保存拒绝时静默，结果存储异常按原契约传播。 */
    suspend fun complete(source: String, target: BackupResultTarget, publicationSucceeded: Boolean,
        record: suspend () -> Boolean): Boolean = feedback.complete(source, target, publicationSucceeded, record)
}
