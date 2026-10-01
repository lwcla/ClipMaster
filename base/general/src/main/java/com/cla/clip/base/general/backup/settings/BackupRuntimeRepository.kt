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

    /** 完整包准备好后才切换指针，失败不会遗失旧包身份。 */
    suspend fun replacePending(pending: BackupPendingUpload) {
        store.edit { preferences ->
            preferences[Keys.PENDING_ARTIFACT_ID] = pending.artifactId
            preferences[Keys.PENDING_TARGET] = pending.targetFingerprint
            preferences[Keys.PENDING_SOURCE] = pending.source
            preferences[Keys.PENDING_PHASE] = pending.phase
            preferences[Keys.PENDING_CREATED_AT] = pending.createdAt
        }
    }

    /** 比较身份后推进阶段，旧 Worker 不能改变新任务。 */
    suspend fun updatePendingPhase(artifactId: String, phase: String): Boolean {
        /** 事务内是否仍拥有当前文件。 */
        var updated = false
        store.edit { preferences ->
            if (preferences[Keys.PENDING_ARTIFACT_ID] == artifactId && !BackupPendingUpload.isTerminalPhase(preferences[Keys.PENDING_PHASE])) {
                preferences[Keys.PENDING_PHASE] = phase
                updated = true
            }
        }
        return updated
    }

    /** 上传结果与终态一次提交；先持久化终态，再清理私有 ZIP。 */
    suspend fun finishPending(artifactId: String, completedAt: Long, reasonCode: String = ""): Boolean {
        /** 身份匹配才能提交远端结果。 */
        var finished = false
        store.edit { preferences ->
            if (preferences[Keys.PENDING_ARTIFACT_ID] == artifactId && !BackupPendingUpload.isTerminalPhase(preferences[Keys.PENDING_PHASE])) {
                preferences.writeResult(BackupResultTarget.WebDav, completedAt, reasonCode)
                preferences[Keys.PENDING_PHASE] = if (reasonCode.isBlank()) "succeeded" else "failed"
                finished = true
            }
        }
        return finished
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
                    preferences[Keys.PENDING_PHASE].orEmpty(), preferences[Keys.PENDING_CREATED_AT] ?: 0L)
            },
            webDavHealth = when (preferences[Keys.WEBDAV_HEALTH]) {
                "available" -> BackupTargetHealth.Available
                "unavailable" -> BackupTargetHealth.Unavailable
                else -> BackupTargetHealth.Unknown
            },
            webDavHealthCheckedAt = preferences[Keys.WEBDAV_HEALTH_CHECKED_AT] ?: 0L,
        )
    }
}
