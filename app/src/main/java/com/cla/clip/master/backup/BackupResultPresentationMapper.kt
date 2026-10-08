package com.cla.clip.master.backup

import com.cla.clip.base.general.backup.settings.*

/** 纯展示分类，无外部序列化或反射依赖。 */
enum class BackupResultKind { Loading, ReadFailed, Success, Failed, Never, LocalUnconfigured, WebDavUnconfigured, WaitingExecution, Generating, SavingLocal, Uploading }

/** 一个目标的历史结果或当前阶段。 */
data class BackupResultPresentation(
    /** 历史结论优先于当前阶段。 */
    val kind: BackupResultKind = BackupResultKind.Loading,
    /** 已完成时间，不能用轮次开始时间替代。 */
    val completedAt: Long = 0L,
    /** 稳定错误 code，不展示异常原文。 */
    val reasonCode: String = "",
)

/** 非终态详情，两目标独立展示，不覆盖历史结果。 */
data class BackupPendingPresentation(
    /** 当前真实排队或运行阶段。 */
    val kind: BackupResultKind,
    /** 本轮开始时间，生成阶段不声称 ZIP 已完成。 */
    val createdAt: Long,
    /** 明确归属，不将本地状态显示成云端状态。 */
    val target: BackupResultTarget = BackupResultTarget.WebDav,
)

/** 两条目标结果在窄屏独立换行。 */
data class BackupEntryState(
    /** 本地发布及系统文件导出的结果。 */
    val local: BackupResultPresentation = BackupResultPresentation(),
    /** 实际云端上传结果。 */
    val webDav: BackupResultPresentation = BackupResultPresentation(),
)

/** 配置和运行态的纯映射，订阅生命周期继续由页面管理。 */
internal object BackupResultPresentationMapper {
    /** 已结束及未选择目标不展示待执行详情；两目标分别映射真实阶段。 */
    fun pending(pending: BackupPendingUpload?): List<BackupPendingPresentation> = buildList {
        if (pending == null) return@buildList
        stage(pending.localPhase)?.let { add(BackupPendingPresentation(it, pending.createdAt, BackupResultTarget.Local)) }
        stage(pending.phase)?.let { add(BackupPendingPresentation(it, pending.createdAt, BackupResultTarget.WebDav)) }
    }

    /** 保留历史成功/失败；私有生成文件不能冒充已发布的本地备份。 */
    fun map(settings: BackupSettingsSnapshot, runtime: BackupRuntimeSnapshot): BackupEntryState = BackupEntryState(
        local = completed(runtime.localResult) ?: BackupResultPresentation(
            if (settings.localBackupDirUri.isBlank()) BackupResultKind.LocalUnconfigured
            else stage(runtime.pendingUpload?.localPhase) ?: BackupResultKind.Never),
        webDav = completed(runtime.webDavResult) ?: BackupResultPresentation(
            if (settings.webDavEndpoint.isBlank()) BackupResultKind.WebDavUnconfigured
            else stage(runtime.pendingUpload?.phase) ?: BackupResultKind.Never),
    )

    /** 固定 code 映射只反映已知阶段，未知历史状态不猜测网络条件。 */
    private fun stage(phase: String?): BackupResultKind? = when (phase) {
        "generating" -> BackupResultKind.Generating
        "ready", "enqueuing", "queued" -> BackupResultKind.WaitingExecution
        "publishing" -> BackupResultKind.SavingLocal
        "uploading" -> BackupResultKind.Uploading
        else -> null
    }

    /** 没有已完成历史时由配置/当前阶段兜底。 */
    private fun completed(result: BackupTargetResult): BackupResultPresentation? = result.takeIf { it.completedAt > 0L }?.let {
        BackupResultPresentation(if (it.failed) BackupResultKind.Failed else BackupResultKind.Success, it.completedAt, it.reasonCode)
    }
}
