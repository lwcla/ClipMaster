package com.cla.clip.master.backup

import com.cla.clip.base.general.backup.settings.*

/** 纯展示分类，不参与序列化或备份协议。 */
enum class BackupResultKind { Loading, ReadFailed, Success, Failed, Never, LocalUnconfigured, WebDavUnconfigured, WaitingWifi, WaitingNetwork, Uploading }

/** 一个结果区域的展示数据。 */
data class BackupResultPresentation(
    /** 完成结果优先，等待和配置状态仅用于没有历史结果时。 */
    val kind: BackupResultKind = BackupResultKind.Loading,
    /** 成功时间戳；失败区域不把此时间显示为成功。 */
    val completedAt: Long = 0L,
    /** 仅供备份页映射可行动提示，入口只显示最近结论。 */
    val reasonCode: String = "",
)

/** 等待包详情与历史结果分开，入口无需显示此区域。 */
data class BackupPendingPresentation(
    /** 当前等待网络策略或上传阶段。 */
    val kind: BackupResultKind,
    /** 生成时快照时间，不冒充远端完成时间。 */
    val createdAt: Long,
)

/** 两条区域独立换行，窄屏不截断第二类结果。 */
data class BackupEntryState(
    /** 本地正式发布及手动文件导出结果。 */
    val local: BackupResultPresentation = BackupResultPresentation(),
    /** 同包远端上传结果。 */
    val webDav: BackupResultPresentation = BackupResultPresentation(),
)

/** 配置与运行态到轻量展示模型的纯映射；不触发 IO。 */
internal object BackupResultPresentationMapper {
    /** 仅非终态包展示详情；自动和手动使用各自真实网络策略。 */
    fun pending(settings: BackupSettingsSnapshot, pending: BackupPendingUpload?): BackupPendingPresentation? {
        if (pending == null || pending.terminal) return null
        return BackupPendingPresentation(
            when {
                pending.phase == "uploading" -> BackupResultKind.Uploading
                BackupSchedulingPolicy.uploadRequiresUnmetered(pending.source, settings.autoBackupOnlyWifi) -> BackupResultKind.WaitingWifi
                else -> BackupResultKind.WaitingNetwork
            }, pending.createdAt)
    }

    /** 历史结论在等待新包时保留；私有包不算本地正式备份。 */
    fun map(settings: BackupSettingsSnapshot, runtime: BackupRuntimeSnapshot): BackupEntryState = BackupEntryState(
        local = completed(runtime.localResult) ?: BackupResultPresentation(
            if (settings.localBackupDirUri.isBlank()) BackupResultKind.LocalUnconfigured else BackupResultKind.Never),
        webDav = completed(runtime.webDavResult) ?: BackupResultPresentation(when {
            settings.webDavEndpoint.isBlank() -> BackupResultKind.WebDavUnconfigured
            runtime.pendingUpload?.terminal == false -> if (BackupSchedulingPolicy.uploadRequiresUnmetered(runtime.pendingUpload?.source.orEmpty(), settings.autoBackupOnlyWifi))
                BackupResultKind.WaitingWifi else BackupResultKind.WaitingNetwork
            else -> BackupResultKind.Never
        }),
    )

    /** 未完成历史返回空，由配置和等待状态补充；不使用全局自动任务结论猜测。 */
    private fun completed(result: BackupTargetResult): BackupResultPresentation? = result.takeIf { it.completedAt > 0L }?.let {
        BackupResultPresentation(if (it.failed) BackupResultKind.Failed else BackupResultKind.Success, it.completedAt, it.reasonCode)
    }
}
