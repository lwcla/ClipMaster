package com.cla.clip.base.general.config.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

/** 宿主设置的持久化键，仅供同模块领域仓库使用。 */
internal object HostSettingsKeys {
    /** 普通剪贴 item 快捷动作的稳定 code。 */
    val CLIP_ITEM_QUICK_ACTION = stringPreferencesKey("clip_item_quick_action")
    /** 来源过滤名单；仓库负责规范化并排序。 */
    val BLOCKED_CLIP_SOURCE_PACKAGES = stringSetPreferencesKey("blocked_clip_source_packages")
    /** 回收站滚动保留天数。 */
    val RECYCLE_BIN_RETENTION_DAYS = intPreferencesKey("recycle_bin_retention_days")
    /** 安装级纯数字身份，禁止进入用户备份。 */
    val INSTALL_ID = stringPreferencesKey("pid")
    /** 主进程当前认可的 Shizuku 完整进程名。 */
    val EXPECTED_PROCESS_NAME = stringPreferencesKey("current_suffix")
    /** 最近一次视频下载任务的持久化指针。 */
    val VIDEO_DOWNLOAD_TASK_ID = longPreferencesKey("video_download_task_id")
    /** 上次自升级自动检查时间，单位毫秒。 */
    val APP_UPDATE_LAST_CHECK_AT = longPreferencesKey("app_update_last_check_at")
    /** 当前广告源选择的稳定标识。 */
    val ACTIVE_AD_SOURCE_ID = stringPreferencesKey("active_ad_source_id")
    /** 本机广告总开关。 */
    val ADS_GLOBAL_ENABLED = booleanPreferencesKey("ads_global_enabled")
    /** 本机广告隐私同意状态的稳定 code。 */
    val AD_CONSENT_STATE = stringPreferencesKey("ad_consent_state")
    /** 已同意的广告隐私政策版本。 */
    val AD_PRIVACY_POLICY_VERSION = stringPreferencesKey("ad_privacy_policy_version")
    /** WebDAV 服务根地址，仅供本机连接。 */
    val WEBDAV_ENDPOINT = stringPreferencesKey("webdav_endpoint")
    /** WebDAV 用户名，仅供本机连接。 */
    val WEBDAV_USERNAME = stringPreferencesKey("webdav_username")
    /** WebDAV 独立 Keystore 密文的版本化载荷。 */
    val WEBDAV_PASSWORD_CIPHERTEXT = stringPreferencesKey("webdav_password_ciphertext")
    /** WebDAV 远端备份目录。 */
    val WEBDAV_REMOTE_DIR = stringPreferencesKey("webdav_remote_dir")
    /** 是否允许明文 HTTP WebDAV。 */
    val WEBDAV_ALLOW_INSECURE_HTTP = booleanPreferencesKey("webdav_allow_insecure_http")
    /** 本地备份目录的设备绑定 SAF URI。 */
    val LOCAL_BACKUP_DIR_URI = stringPreferencesKey("local_backup_dir_uri")
    /** 本地备份目录的展示名称。 */
    val LOCAL_BACKUP_DIR_LABEL = stringPreferencesKey("local_backup_dir_label")
    /** 自动备份本机执行开关。 */
    val AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
    /** 用户对明文 ZIP 风险的本机披露确认。 */
    val BACKUP_PLAINTEXT_DISCLOSURE_ACCEPTED = booleanPreferencesKey("backup_plaintext_disclosure_accepted")
    /** 普通备份目标的保留份数。 */
    val BACKUP_RETENTION_COUNT = intPreferencesKey("backup_retention_count")
    /** WebDAV 自动备份仅 Wi-Fi 约束。 */
    val AUTO_BACKUP_ONLY_WIFI = booleanPreferencesKey("auto_backup_only_wifi")
    /** 当前可备份数据的变更版本。 */
    val CHANGE_REVISION = longPreferencesKey("backup_change_revision")
    /** 已成功覆盖的最高备份版本。 */
    val BACKED_UP_REVISION = longPreferencesKey("backup_backed_up_revision")
    /** 最近自动备份任务状态的稳定 code。 */
    val LAST_AUTO_BACKUP_STATUS = stringPreferencesKey("last_auto_backup_status")
    /** 最近自动备份成功时间，单位毫秒。 */
    val LAST_AUTO_BACKUP_SUCCESS_AT = longPreferencesKey("last_auto_backup_success_at")
    /** 最近自动备份失败原因的低敏 code。 */
    val LAST_AUTO_BACKUP_FAILURE_REASON = stringPreferencesKey("last_auto_backup_failure_reason")
    /** 最近自动备份跳过原因的低敏 code。 */
    val LAST_AUTO_BACKUP_SKIP_REASON = stringPreferencesKey("last_auto_backup_skip_reason")
    /** WebDAV 目标最近健康状态的稳定 code。 */
    val WEBDAV_HEALTH = stringPreferencesKey("webdav_health")
    /** WebDAV 最近健康检查时间，单位毫秒。 */
    val WEBDAV_HEALTH_CHECKED_AT = longPreferencesKey("webdav_health_checked_at")
    /** 最近一次成功备份的小型摘要 JSON。 */
    val LAST_BACKUP_SUCCESS_SUMMARY = stringPreferencesKey("last_backup_success_summary")
}
