package com.cla.clip.base.general.backup.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.cla.clip.base.general.backup.BackupTargetHealth
import com.cla.clip.base.general.config.datastore.HostSettingsKeys as Keys
import com.cla.clip.base.general.config.datastore.HostSettingsStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

/** 分目标结果与文件身份；测试连接和备份结果互不覆盖。 */
data class BackupRuntimeSnapshot(
    /** 本地正式发布或手动导出的最近结果。 */
    val localResult: BackupTargetResult = BackupTargetResult(),
    /** WebDAV 实际上传的最近结果。 */
    val webDavResult: BackupTargetResult = BackupTargetResult(),
    /** 私有同包上传状态。 */
    val pendingUpload: BackupPendingUpload? = null,
    /** 连接测试缓存，不表示备份成功。 */
    val webDavHealth: BackupTargetHealth = BackupTargetHealth.Unknown,
    /** 最近连接测试时间。 */
    val webDavHealthCheckedAt: Long = 0L,
    /** 最新轮次身份，空值代表仍需释放旧上传状态。 */
    val currentRoundId: String = "",
)

/** 只保存备份结果和文件身份；业务保存不再依赖备份运行态。 */
@Singleton
class BackupRuntimeRepository @Inject internal constructor(
    /** 安装内设置的唯一事务入口。 */
    private val store: HostSettingsStore,
) {
    /** 每个仓库仅核对一次旧键；多实例并发编辑仍由 DataStore 事务保证幂等。 */
    @Volatile private var legacyChecked = false

    /** 展示订阅由页面生命周期管理。 */
    val data = store.data.onStart { migrateLegacy() }.map(::decode).distinctUntilChanged()

    /** IO 失败向调用方传播，不伪装为未备份。 */
    suspend fun snapshot(): BackupRuntimeSnapshot {
        migrateLegacy()
        return decode(store.snapshot())
    }

    /** 兼容事务失败向入口传播，不能把不可读存储伪装为全新安装。 */
    private suspend fun migrateLegacy() {
        if (legacyChecked) return
        store.edit(BackupLegacyRuntimeMigration::apply)
        legacyChecked = true
    }

    /** 仅更新实际执行的目标，非空错误码表示失败。 */
    suspend fun recordTargetResult(target: BackupResultTarget, completedAt: Long, reasonCode: String = "") {
        store.edit { preferences -> preferences.writeResult(target, completedAt, reasonCode) }
    }

    /** 新轮次开始先切换身份；此事务成功后才允许删除旧内部包。 */
    suspend fun replacePending(pending: BackupPendingUpload) {
        migrateLegacy()
        store.edit { preferences ->
            /** 相同身份只是描述更新，只有新 taskId 才重置目录许可。 */
            val newTask = preferences[Keys.PENDING_ARTIFACT_ID] != pending.artifactId
            preferences[Keys.CURRENT_BACKUP_ROUND_ID] = pending.artifactId
            preferences[Keys.PENDING_ARTIFACT_ID] = pending.artifactId
            preferences[Keys.PENDING_TARGET] = pending.targetFingerprint
            preferences[Keys.PENDING_SOURCE] = pending.source
            preferences[Keys.PENDING_PHASE] = pending.phase
            preferences[Keys.PENDING_CREATED_AT] = pending.createdAt
            preferences[Keys.PENDING_LOCAL_TARGET] = pending.localTargetFingerprint
            preferences[Keys.PENDING_LOCAL_PHASE] = pending.localPhase
            // 新任务身份才重置许可，阶段更新和系统重调度不写此字段。
            if (newTask) preferences[Keys.PENDING_WEBDAV_DIRECTORY_RECOVERY_ATTEMPTED] = false
        }
    }

    /** 两消费者开始前读取任务身份，不让旧请求借用新包。 */
    suspend fun isCurrentRound(roundId: String): Boolean = snapshot().currentRoundId == roundId

    /** 404/409 共用一次目录许可，先在当前未终结任务的事务中落盘；写盘失败直接传播，不允许修复。 */
    suspend fun claimWebDavDirectoryRecoveryIfCurrentRound(roundId: String): Boolean {
        /** 只有本次成功落盘置位的调用获得许可，多调用及重建仓库均不能再次取得。 */
        var claimed = false
        store.edit { preferences ->
            if (preferences[Keys.CURRENT_BACKUP_ROUND_ID] == roundId &&
                preferences[Keys.PENDING_ARTIFACT_ID] == roundId &&
                preferences[Keys.PENDING_PHASE] in setOf("ready", "queued", "uploading") &&
                preferences[Keys.PENDING_WEBDAV_DIRECTORY_RECOVERY_ATTEMPTED] != true) {
                preferences[Keys.PENDING_WEBDAV_DIRECTORY_RECOVERY_ATTEMPTED] = true
                claimed = true
            }
        }
        return claimed
    }

    /** 两目标终态独立，事务内比较最新 ID 后才保存结果；迟到或重复提交返回 false。 */
    suspend fun recordTargetResultIfCurrentRound(roundId: String, target: BackupResultTarget,
        completedAt: Long, reasonCode: String = ""): Boolean {
        /** 只有结果实际保存后，调用方才允许保留清理。 */
        var recorded = false
        store.edit { preferences ->
            /** 目标决定独立阶段键，不通过枚举名称持久化。 */
            val phaseKey = if (target == BackupResultTarget.Local) Keys.PENDING_LOCAL_PHASE else Keys.PENDING_PHASE
            if (preferences[Keys.CURRENT_BACKUP_ROUND_ID] == roundId &&
                !BackupPendingUpload.isTerminalPhase(preferences[phaseKey])) {
                preferences.writeResult(target, completedAt, reasonCode)
                preferences[phaseKey] = if (reasonCode.isBlank()) "succeeded" else "failed"
                recorded = true
            }
        }
        return recorded
    }

    /** 推进本地阶段或取消目标；已结束和过期请求不能重新运行。 */
    suspend fun updateTargetPhase(roundId: String, target: BackupResultTarget, phase: String): Boolean {
        /** 是否仍拥有未结束的当前目标。 */
        var updated = false
        store.edit { preferences ->
            /** 每个目标独立的稳定阶段键。 */
            val key = if (target == BackupResultTarget.Local) Keys.PENDING_LOCAL_PHASE else Keys.PENDING_PHASE
            if (preferences[Keys.CURRENT_BACKUP_ROUND_ID] == roundId && !BackupPendingUpload.isTerminalPhase(preferences[key])) {
                preferences[key] = phase
                updated = true
            }
        }
        return updated
    }

    /** 完整包发布后在同一事务推进两个选中目标，不暴露半包或未选择目标。 */
    suspend fun markRoundReady(roundId: String) {
        store.edit { preferences ->
            if (preferences[Keys.CURRENT_BACKUP_ROUND_ID] == roundId) {
                // key 分别定位两目标阶段，只将已选择的生成中目标推进为可消费。
                for (key in listOf(Keys.PENDING_LOCAL_PHASE, Keys.PENDING_PHASE)) {
                    if (preferences[key] == "generating") preferences[key] = "ready"
                }
            }
        }
    }

    /** 文件清理后移除对应指针，不会删除随后替换的新包。 */
    suspend fun clearPending(artifactId: String) {
        store.edit { preferences ->
            if (preferences[Keys.PENDING_ARTIFACT_ID] == artifactId) {
                preferences.remove(Keys.PENDING_ARTIFACT_ID)
                preferences.remove(Keys.PENDING_TARGET)
                preferences.remove(Keys.PENDING_SOURCE)
                preferences.remove(Keys.PENDING_PHASE)
                preferences.remove(Keys.PENDING_CREATED_AT)
                preferences.remove(Keys.PENDING_LOCAL_TARGET)
                preferences.remove(Keys.PENDING_LOCAL_PHASE)
                preferences.remove(Keys.PENDING_WEBDAV_DIRECTORY_RECOVERY_ATTEMPTED)
            }
        }
    }

    /** 连接测试只更新健康状态，不影响两类上次备份。 */
    suspend fun setWebDavHealth(health: BackupTargetHealth, checkedAt: Long) {
        store.edit { preferences ->
            preferences[Keys.WEBDAV_HEALTH] = when (health) {
                BackupTargetHealth.Available -> "available"
                BackupTargetHealth.Unavailable -> "unavailable"
                BackupTargetHealth.Unknown -> "unknown"
            }
            preferences[Keys.WEBDAV_HEALTH_CHECKED_AT] = checkedAt.coerceAtLeast(0L)
        }
    }

    /** 固定字段写入；枚举只决定键，不保存名称或序号。 */
    private fun MutablePreferences.writeResult(target: BackupResultTarget, completedAt: Long, reasonCode: String) {
        /** 每个目标有独立键组。 */
        val local = target == BackupResultTarget.Local
        this[if (local) Keys.LOCAL_RESULT_AT else Keys.WEBDAV_RESULT_AT] = completedAt.coerceAtLeast(1L)
        this[if (local) Keys.LOCAL_RESULT_FAILED else Keys.WEBDAV_RESULT_FAILED] = reasonCode.isNotBlank()
        this[if (local) Keys.LOCAL_RESULT_REASON else Keys.WEBDAV_RESULT_REASON] = reasonCode
    }

    /** 新结果采用固定字段读取；旧兼容在事务内完成，不在每次展示时猜测。 */
    private fun decode(preferences: Preferences): BackupRuntimeSnapshot {
        /** 空身份表示没有待上传任务。 */
        val artifactId = preferences[Keys.PENDING_ARTIFACT_ID].orEmpty()
        return BackupRuntimeSnapshot(
            localResult = BackupTargetResult(
                preferences[Keys.LOCAL_RESULT_AT] ?: 0L,
                preferences[Keys.LOCAL_RESULT_FAILED] ?: false, preferences[Keys.LOCAL_RESULT_REASON].orEmpty()),
            webDavResult = BackupTargetResult(
                preferences[Keys.WEBDAV_RESULT_AT] ?: 0L,
                preferences[Keys.WEBDAV_RESULT_FAILED] ?: false, preferences[Keys.WEBDAV_RESULT_REASON].orEmpty()),
            pendingUpload = artifactId.takeIf(String::isNotBlank)?.let {
                BackupPendingUpload(it, preferences[Keys.PENDING_TARGET].orEmpty(), preferences[Keys.PENDING_SOURCE].orEmpty(),
                    preferences[Keys.PENDING_PHASE].orEmpty(), preferences[Keys.PENDING_CREATED_AT] ?: 0L,
                    preferences[Keys.PENDING_LOCAL_TARGET].orEmpty(), preferences[Keys.PENDING_LOCAL_PHASE] ?: "unselected",
                    preferences[Keys.PENDING_WEBDAV_DIRECTORY_RECOVERY_ATTEMPTED] ?: false)
            },
            webDavHealth = when (preferences[Keys.WEBDAV_HEALTH]) {
                "available" -> BackupTargetHealth.Available
                "unavailable" -> BackupTargetHealth.Unavailable
                else -> BackupTargetHealth.Unknown
            },
            webDavHealthCheckedAt = preferences[Keys.WEBDAV_HEALTH_CHECKED_AT] ?: 0L,
            currentRoundId = preferences[Keys.CURRENT_BACKUP_ROUND_ID].orEmpty(),
        )
    }
}
