package com.cla.clip.base.general.backup.settings

import androidx.datastore.preferences.core.Preferences
import com.cla.clip.base.general.backup.BackupJson
import com.cla.clip.base.general.backup.BackupSuccessSummary
import com.cla.clip.base.general.backup.BackupTargetHealth
import com.cla.clip.base.general.backup.BackupTaskStatus
import com.cla.clip.base.general.config.datastore.HostSettingsKeys
import com.cla.clip.base.general.config.datastore.HostSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 本机备份任务状态快照；版本差值是唯一的 dirty 来源。 */
data class BackupRuntimeSnapshot(
    /** 本次安装内已提交的可备份数据变更版本。 */
    val changeRevision: Long,
    /** 已由所有目标完整备份的最近版本。 */
    val backedUpRevision: Long,
    /** 最近自动备份任务状态。 */
    val status: BackupTaskStatus,
    /** 最近成功备份时间，0 表示尚无成功记录。 */
    val successAt: Long,
    /** 已脱敏的最近失败原因；页面负责决定如何展示。 */
    val failureReason: String,
    /** 已脱敏的最近跳过原因。 */
    val skipReason: String,
    /** 最近一次 WebDAV 连通性检查状态。 */
    val webDavHealth: BackupTargetHealth,
    /** 最近一次 WebDAV 连通性检查时间。 */
    val webDavHealthCheckedAt: Long,
    /** 最近成功备份摘要，损坏摘要只在展示上回退为空。 */
    val successSummary: BackupSuccessSummary?,
) {
    /** 当前仍有变更未包含进所有已确认目标的备份。 */
    val dirty: Boolean get() = changeRevision != backedUpRevision
}

/** DataStore 中的备份运行态：负责版本确认与一次事务内更新相关状态。 */
@Singleton
class BackupRuntimeRepository @Inject internal constructor(
    /** 唯一宿主设置文件；原始 key 仅在数据层使用。 */
    private val store: HostSettingsStore,
) {
    /** 观察低敏运行状态，不在页面维护第二份可写镜像。 */
    val data: Flow<BackupRuntimeSnapshot> = store.data.map(::decode).distinctUntilChanged()

    /** 获取当前已提交状态；IO 失败由调用者处理，不能当作空配置。 */
    suspend fun snapshot(): BackupRuntimeSnapshot = decode(store.snapshot())

    /** 在写入真正的可备份业务变化后递增版本；完成前不返回成功。 */
    suspend fun markChanged() {
        store.edit { preferences ->
            /** 事务内最新变更版本，避免并发写入丢失递增。 */
            val current = preferences[HostSettingsKeys.CHANGE_REVISION] ?: INITIAL_CHANGE_REVISION
            check(current < Long.MAX_VALUE) { "backup_change_revision_exhausted" }
            preferences[HostSettingsKeys.CHANGE_REVISION] = current + 1L
        }
    }

    /** 导出开始前捕获版本；后续更新必须留给下一次备份。 */
    suspend fun captureRevision(): Long = snapshot().changeRevision

    /** 只确认导出时捕获且当前仍存在的版本，避免旧备份吞掉并发新变化。 */
    suspend fun acknowledgeRevision(revision: Long) {
        store.edit { preferences ->
            /** 事务内最新业务变更版本。 */
            val current = preferences[HostSettingsKeys.CHANGE_REVISION] ?: INITIAL_CHANGE_REVISION
            /** 此前已完整备份的版本，不允许倒退。 */
            val previous = preferences[HostSettingsKeys.BACKED_UP_REVISION] ?: INITIAL_BACKED_UP_REVISION
            if (revision > previous && revision <= current) {
                preferences[HostSettingsKeys.BACKED_UP_REVISION] = revision
            }
        }
    }

    /** 一次写入任务状态及失败/跳过原因，避免页面观察到互相矛盾的组合。 */
    suspend fun recordStatus(status: BackupTaskStatus, failureReason: String = "", skipReason: String = "") {
        store.edit { preferences ->
            preferences[HostSettingsKeys.LAST_AUTO_BACKUP_STATUS] = status.storageCode()
            preferences[HostSettingsKeys.LAST_AUTO_BACKUP_FAILURE_REASON] = failureReason
            preferences[HostSettingsKeys.LAST_AUTO_BACKUP_SKIP_REASON] = skipReason
        }
    }

    /** 备份成功后一次写入状态、摘要及可选的已覆盖版本，避免成功展示和版本确认分离。 */
    suspend fun recordSuccess(
        status: BackupTaskStatus,
        summary: BackupSuccessSummary,
        failureReason: String = "",
        acknowledgedRevision: Long? = null,
    ) {
        store.edit { preferences ->
            preferences[HostSettingsKeys.LAST_AUTO_BACKUP_STATUS] = status.storageCode()
            preferences[HostSettingsKeys.LAST_AUTO_BACKUP_SUCCESS_AT] = summary.createdAt
            preferences[HostSettingsKeys.LAST_BACKUP_SUCCESS_SUMMARY] = BackupJson.encodeSuccessSummary(summary)
            preferences[HostSettingsKeys.LAST_AUTO_BACKUP_FAILURE_REASON] = failureReason
            preferences[HostSettingsKeys.LAST_AUTO_BACKUP_SKIP_REASON] = ""
            if (acknowledgedRevision != null) {
                /** 仅确认当前仍存在且尚未确认的导出版本，后续并发变化仍保持 dirty。 */
                val current = preferences[HostSettingsKeys.CHANGE_REVISION] ?: INITIAL_CHANGE_REVISION
                /** 此前完整备份的版本不得倒退。 */
                val previous = preferences[HostSettingsKeys.BACKED_UP_REVISION] ?: INITIAL_BACKED_UP_REVISION
                if (acknowledgedRevision > previous && acknowledgedRevision <= current) {
                    preferences[HostSettingsKeys.BACKED_UP_REVISION] = acknowledgedRevision
                }
            }
        }
    }

    /** 配置变更或检查完成时原子更新 WebDAV 健康状态和时间。 */
    suspend fun setWebDavHealth(health: BackupTargetHealth, checkedAt: Long) {
        store.edit { preferences ->
            preferences[HostSettingsKeys.WEBDAV_HEALTH] = health.storageCode()
            preferences[HostSettingsKeys.WEBDAV_HEALTH_CHECKED_AT] = checkedAt.coerceAtLeast(0L)
        }
    }

    /** 从单份磁盘快照解码为强类型运行状态；坏摘要不会在读取时隐式写盘。 */
    private fun decode(preferences: Preferences): BackupRuntimeSnapshot {
        /** 已保存的摘要编码；只有非空内容才尝试解析。 */
        val summaryText = preferences[HostSettingsKeys.LAST_BACKUP_SUCCESS_SUMMARY]
        /** 可用于显示的成功摘要；损坏值只在展示时降级。 */
        val summary = summaryText?.takeIf(String::isNotBlank)?.let { runCatching { BackupJson.decodeSuccessSummary(it) }.getOrNull() }
        return BackupRuntimeSnapshot(
            changeRevision = preferences[HostSettingsKeys.CHANGE_REVISION] ?: INITIAL_CHANGE_REVISION,
            backedUpRevision = preferences[HostSettingsKeys.BACKED_UP_REVISION] ?: INITIAL_BACKED_UP_REVISION,
            status = parseStatus(preferences[HostSettingsKeys.LAST_AUTO_BACKUP_STATUS]),
            successAt = preferences[HostSettingsKeys.LAST_AUTO_BACKUP_SUCCESS_AT] ?: 0L,
            failureReason = preferences[HostSettingsKeys.LAST_AUTO_BACKUP_FAILURE_REASON].orEmpty(),
            skipReason = preferences[HostSettingsKeys.LAST_AUTO_BACKUP_SKIP_REASON].orEmpty(),
            webDavHealth = parseHealth(preferences[HostSettingsKeys.WEBDAV_HEALTH]),
            webDavHealthCheckedAt = preferences[HostSettingsKeys.WEBDAV_HEALTH_CHECKED_AT] ?: 0L,
            successSummary = summary,
        )
    }

    /** 将任务状态映射为与枚举混淆名称无关的持久化 code。 */
    private fun BackupTaskStatus.storageCode(): String = when (this) {
        BackupTaskStatus.Idle -> "idle"
        BackupTaskStatus.Running -> "running"
        BackupTaskStatus.Success -> "success"
        BackupTaskStatus.PartialSuccess -> "partial_success"
        BackupTaskStatus.Skipped -> "skipped"
        BackupTaskStatus.RetryScheduled -> "retry_scheduled"
        BackupTaskStatus.Failed -> "failed"
    }

    /** 未知持久化状态回退 idle，不影响业务任务重新判断。 */
    private fun parseStatus(code: String?): BackupTaskStatus = when (code) {
        "running" -> BackupTaskStatus.Running
        "success" -> BackupTaskStatus.Success
        "partial_success" -> BackupTaskStatus.PartialSuccess
        "skipped" -> BackupTaskStatus.Skipped
        "retry_scheduled" -> BackupTaskStatus.RetryScheduled
        "failed" -> BackupTaskStatus.Failed
        else -> BackupTaskStatus.Idle
    }

    /** WebDAV 健康状态只保存稳定 code，不依赖枚举 name。 */
    private fun BackupTargetHealth.storageCode(): String = when (this) {
        BackupTargetHealth.Unknown -> "unknown"
        BackupTargetHealth.Available -> "available"
        BackupTargetHealth.Unavailable -> "unavailable"
    }

    /** 未知健康值回退 unknown，不能据此对不可验证的地址发起自动上传。 */
    private fun parseHealth(code: String?): BackupTargetHealth = when (code) {
        "available" -> BackupTargetHealth.Available
        "unavailable" -> BackupTargetHealth.Unavailable
        else -> BackupTargetHealth.Unknown
    }

    /** 初始版本和成功版本不同，保证新安装首次备份不会被跳过。 */
    private companion object {
        /** 首次安装时的待备份版本。 */
        const val INITIAL_CHANGE_REVISION = 1L
        /** 尚无任何成功备份时的已确认版本。 */
        const val INITIAL_BACKED_UP_REVISION = 0L
    }
}
