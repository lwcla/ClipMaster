package com.cla.clip.base.general.backup.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cla.clip.base.general.backup.BackupJson
import com.cla.clip.base.general.config.datastore.HostSettingsKeys as Keys

/** 安装内旧运行态兼容；业务写入不接入此逻辑，旧键只在清理时识别。 */
internal object BackupLegacyRuntimeMigration {
    /** 唯一旧状态读取入口；其他全局键只按精确名称移除。 */
    private val oldStatus = stringPreferencesKey("last_auto_backup_status")
    /** 不涉及业务设置、凭据或数据库的固定清理白名单。 */
    private val obsoleteNames = setOf("backup_change_revision", "backup_backed_up_revision", "last_auto_backup_status",
        "last_auto_backup_success_at", "last_auto_backup_failure_reason", "last_auto_backup_skip_reason", "last_backup_success_summary",
        "auto_backup_only_wifi")

    /** 旧摘要回填与旧键释放在同一事务；失败状态不猜测最近结果，新结果永远优先。 */
    fun apply(preferences: MutablePreferences) {
        /** 只有最近全局任务成功时，摘要才能作为兼容成功结果。 */
        val successful = preferences[oldStatus] in setOf("success", "partial_success")
        /** 损坏历史摘要不能阻断备份入口，也不能被解释为成功。 */
        val summary = preferences[Keys.LAST_BACKUP_SUCCESS_SUMMARY]?.let {
            runCatching { BackupJson.decodeSuccessSummary(it) }.getOrNull()
        }
        if (successful && summary != null && summary.createdAt > 0L) {
            if (summary.localSuccess && !preferences.contains(Keys.LOCAL_RESULT_AT)) {
                preferences[Keys.LOCAL_RESULT_AT] = summary.createdAt
                preferences[Keys.LOCAL_RESULT_FAILED] = false
                preferences[Keys.LOCAL_RESULT_REASON] = ""
            }
            if (summary.webDavSuccess && !preferences.contains(Keys.WEBDAV_RESULT_AT)) {
                preferences[Keys.WEBDAV_RESULT_AT] = summary.createdAt
                preferences[Keys.WEBDAV_RESULT_FAILED] = false
                preferences[Keys.WEBDAV_RESULT_REASON] = ""
            }
        }
        // 先复制 key 集合再删除，避免遍历可写快照时改变集合。
        preferences.asMap().keys.filter { it.name in obsoleteNames }.forEach { key -> preferences.remove(key) }
    }
}
