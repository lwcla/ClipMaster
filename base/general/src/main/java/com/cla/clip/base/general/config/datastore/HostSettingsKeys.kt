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
    /** 安装级身份，首次生成采用 20 位纯数字，已有非空值原样保留；禁止进入用户备份。 */
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
    /** 安装内最新轮次，旧消费请求不能覆盖新结果。 */
    val CURRENT_BACKUP_ROUND_ID = stringPreferencesKey("backup_current_round_id")
    /** 本地目标身份摘要，不持久化 SAF 授权原文。 */
    val PENDING_LOCAL_TARGET = stringPreferencesKey("backup_pending_local_target")
    /** 本地独立阶段，未选择目标使用 unselected。 */
    val PENDING_LOCAL_PHASE = stringPreferencesKey("backup_pending_local_phase")
    /** WebDAV 目标最近健康状态的稳定 code。 */
    val WEBDAV_HEALTH = stringPreferencesKey("webdav_health")
    /** WebDAV 最近健康检查时间，单位毫秒。 */
    val WEBDAV_HEALTH_CHECKED_AT = longPreferencesKey("webdav_health_checked_at")
    /** 仅用于旧运行态兼容读取，事务迁移后删除，不再写入。 */
    val LAST_BACKUP_SUCCESS_SUMMARY = stringPreferencesKey("last_backup_success_summary")
    /** 本地最近完成时间，零/缺键表示尚无结果；不进入用户备份。 */
    val LOCAL_RESULT_AT = longPreferencesKey("backup_local_result_at")
    /** 本地最近一次是否失败，与等待状态分开；不进入用户备份。 */
    val LOCAL_RESULT_FAILED = booleanPreferencesKey("backup_local_result_failed")
    /** 本地失败的稳定低敏原因，不保存异常原文；不进入用户备份。 */
    val LOCAL_RESULT_REASON = stringPreferencesKey("backup_local_result_reason")
    /** 远端最近发布完成时间，不使用生成时间替代；不进入用户备份。 */
    val WEBDAV_RESULT_AT = longPreferencesKey("backup_webdav_result_at")
    /** 远端最近一次是否失败，本地成功不改变此值；不进入用户备份。 */
    val WEBDAV_RESULT_FAILED = booleanPreferencesKey("backup_webdav_result_failed")
    /** 远端失败的稳定低敏原因，不含凭据或地址；不进入用户备份。 */
    val WEBDAV_RESULT_REASON = stringPreferencesKey("backup_webdav_result_reason")
    /** 当前私有包身份，只由生成/上传管理，不是业务变化版本。 */
    val PENDING_ARTIFACT_ID = stringPreferencesKey("backup_pending_artifact_id")
    /** 待上传目的地摘要，阻止旧包误发到新配置；不进入用户备份。 */
    val PENDING_TARGET = stringPreferencesKey("backup_pending_target")
    /** auto/manual 固定来源决定等待网络策略；不进入用户备份。 */
    val PENDING_SOURCE = stringPreferencesKey("backup_pending_source")
    /** 私有包固定阶段 code，终态禁止重新入队；不进入用户备份。 */
    val PENDING_PHASE = stringPreferencesKey("backup_pending_phase")
    /** 待上传快照生成时间，供详情说明而非远端成功判断。 */
    val PENDING_CREATED_AT = longPreferencesKey("backup_pending_created_at")
    /** 同一 taskId 的 PUT 404/409 共用一次目录处理许可，安装内临时状态，不备份。 */
    val PENDING_WEBDAV_DIRECTORY_RECOVERY_ATTEMPTED = booleanPreferencesKey("backup_pending_webdav_directory_recovery_attempted")
}
