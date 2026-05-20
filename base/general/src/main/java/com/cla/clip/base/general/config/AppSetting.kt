package com.cla.clip.base.general.config

import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.backup.BackupJson
import com.cla.clip.base.general.backup.BackupSuccessSummary
import com.cla.clip.base.general.backup.BackupTargetHealth
import com.cla.clip.base.general.backup.BackupTaskStatus
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 普通剪贴列表 item 快捷动作区点击动作。
 *
 * 该枚举属于跨页面设置契约：我的页负责选择和保存，普通列表/普通搜索负责读取并映射到具体回调。
 * 后续新增动作时必须同步扩展字符串资源、设置弹窗、共享 item 动作映射和方案文档，避免设置项与实际行为脱节。
 */
enum class ClipItemQuickAction(
    /** 持久化到 MMKV 的稳定值，不能随枚举名重命名而改变。 */
    val storageValue: String,
) {
    /** 快捷动作区点击复制剪贴内容，是默认行为。 */
    Copy("copy"),

    /** 快捷动作区点击切换置顶/取消置顶，语义与右滑菜单保持一致。 */
    Pin("pin"),

    /** 快捷动作区点击打开删除选择弹窗，不允许静默直接删除。 */
    Delete("delete"),

    /** 快捷动作区点击折叠数据，只在普通列表和普通搜索中生效。 */
    Fold("fold"),

    /** 关闭快捷动作区，整张 item 点击进入详情。 */
    None("none");

    companion object {
        /** 默认快捷动作；兼顾快捷复制和当前用户习惯。 */
        val Default = Copy

        /**
         * 将持久化字符串恢复为枚举。
         *
         * 未知值会回退到默认复制，确保旧版本或异常写入不会让列表失去可用点击动作。
         */
        fun fromStorageValue(value: String?): ClipItemQuickAction {
            return entries.firstOrNull { it.storageValue == value } ?: Default
        }
    }
}

/**
 * 应用级轻量配置中心。
 *
 * 基于 MMKV 保存跨进程或跨启动需要读取的小型状态；这里不存放大对象和用户可见内容，避免配置文件膨胀或隐私边界不清。
 */
object AppSetting {

    /** 日志标签，用于排查配置初始化和持久化写入。 */
    private const val TAG = "AppSetting"

    /** 默认 MMKV 实例，首次访问时初始化；调用方需要确保 BaseApplication 已在主进程初始化 MMKV。 */
    private val mmkv by lazy { MMKV.defaultMMKV() }

    /** 独立保存敏感小配置的 MMKV 文件名；系统 Auto Backup 已排除整个 MMKV 目录，避免跨设备恢复设备绑定密文。 */
    private const val SECURE_MMKV_ID = "app_setting_secure"

    /**
     * 加密 MMKV 的本地文件密钥。
     *
     * 这里用于避免 WebDAV 密码继续落在默认明文配置文件中，不等同于用户可管理的端到端备份加密密码。
     * 后续如果接入 Android Keystore，需要在方案文档里同步说明系统恢复和密文失效边界。
     */
    private const val SECURE_MMKV_CRYPT_KEY = "ClipMasterKeyV1!"

    /** 加密 MMKV 实例，仅保存密码这类不应进入默认配置文件的小字段。 */
    private val secureMmkv by lazy {
        MMKV.mmkvWithID(SECURE_MMKV_ID, MMKV.SINGLE_PROCESS_MODE, SECURE_MMKV_CRYPT_KEY)
    }

    /** 视频下载任务 ID，值为 -1 表示没有正在下载的任务 */
    private const val KEY_VIDEO_DOWNLOAD_TASK_ID = "video_download_task_id"

    /** 最近一次视频下载任务 id，-1 表示没有记录；用于启动新下载前清理上次未完成的 pending 输出。 */
    var videoDownloadTaskId
        get() = mmkv.getLong(KEY_VIDEO_DOWNLOAD_TASK_ID, -1L)
        set(value) {
            mmkv.putLong(KEY_VIDEO_DOWNLOAD_TASK_ID, value)
        }

    /**
     * app安装之后生成的一个唯一值，只要app不被卸载，这个值就不会变化
     * 但app卸载之后重新安装，这个值会变化
     * 这个值可以用来区分不同的安装，或者在app被卸载之后重新安装时，识别出这是一个新的安装
     * 这个值不包含任何个人隐私信息，也不会被用来追踪用户，只是一个随机生成的UUID
     */
    private const val KEY_PID = "pid"

    /** 当前安装实例的随机 UUID，不包含用户身份信息；卸载重装后会变化。 */
    val pid: String
        get() {
            var value = mmkv.getString(KEY_PID, null)
            if (value.isNullOrBlank()) {
                logE(TAG) { "创建pid" }
                val uuid = UUID.randomUUID().toString()
                mmkv.putString(KEY_PID, uuid)
                value = uuid
            }

            return value
        }


    /**
     * shizuku进程名
     * 用来在shizuku进程启动之后，检查当前是否为最新的shizuku进程，清理掉旧的shizuku进程
     */
    private const val KEY_SHIZUKU_SUFFIX = "current_suffix"

    /** 当前有效的 Shizuku 进程后缀，不包含冒号；用于识别和清理旧 Shizuku 进程。 */
    var shizukuSuffix: String
        get() = mmkv.getString(KEY_SHIZUKU_SUFFIX, null)?.removePrefix(":")?.takeIf { it.isNotBlank() } ?: ""
        set(value) {
            // current suffix 只由 App 进程写入。Shizuku 进程只能通过 Provider/callback
            // 读取，不能反写，避免旧 Shizuku 进程把新 suffix 覆盖回旧值。
            mmkv.putString(KEY_SHIZUKU_SUFFIX, value.removePrefix(":"))
        }

    /** 权限说明是否已经展开，保存用户在“我的”页的折叠偏好。 */
    private const val KEY_PERMISSION_EXPANDED = "permission_expanded"

    /** “权限说明”卡片展开状态；只影响 UI 展示，不参与权限判断。 */
    var permissionExpanded: Boolean
        get() = mmkv.getBoolean(KEY_PERMISSION_EXPANDED, false)
        set(value) {
            mmkv.putBoolean(KEY_PERMISSION_EXPANDED, value)
        }

    /** 剪贴 item 快捷动作配置 key；值使用 `ClipItemQuickAction.storageValue`，当前功能较新，重命名后不迁移旧 key。 */
    private const val KEY_CLIP_ITEM_QUICK_ACTION = "clip_item_quick_action"

    /** 快捷动作状态流；页面订阅后可以在我的页修改设置时即时刷新普通列表和普通搜索点击行为。 */
    private val _clipItemQuickActionFlow by lazy {
        MutableStateFlow(clipItemQuickAction)
    }

    /** 普通剪贴列表 item 快捷动作点击动作流。 */
    val clipItemQuickActionFlow: StateFlow<ClipItemQuickAction>
        get() = _clipItemQuickActionFlow.asStateFlow()

    /** 普通剪贴列表 item 快捷动作；保存后同步更新状态流。 */
    var clipItemQuickAction: ClipItemQuickAction
        get() = ClipItemQuickAction.fromStorageValue(
            mmkv.getString(
                KEY_CLIP_ITEM_QUICK_ACTION,
                ClipItemQuickAction.Default.storageValue
            )
        )
        set(value) {
            mmkv.putString(KEY_CLIP_ITEM_QUICK_ACTION, value.storageValue)
            _clipItemQuickActionFlow.value = value
            markBackupDirty()
        }

    /** 回收站默认保留天数，单位天；默认 30 天，和产品默认自动清理策略保持一致。 */
    const val DEFAULT_RECYCLE_BIN_RETENTION_DAYS = 30

    /** 回收站保留天数最小值，避免保存 0 或负数导致所有回收站数据立即过期。 */
    const val MIN_RECYCLE_BIN_RETENTION_DAYS = 1

    /** 回收站保留天数最大值，限制自定义输入范围，避免毫秒换算溢出或产生不合理承诺。 */
    const val MAX_RECYCLE_BIN_RETENTION_DAYS = 3650

    /** 回收站保留天数配置 key；值按滚动 24 小时窗口解释，不按自然日零点截断。 */
    private const val KEY_RECYCLE_BIN_RETENTION_DAYS = "recycle_bin_retention_days"

    /** 回收站保留天数；保存时会裁剪到 1 到 3650 天之间。 */
    var recycleBinRetentionDays: Int
        get() = mmkv.getInt(KEY_RECYCLE_BIN_RETENTION_DAYS, DEFAULT_RECYCLE_BIN_RETENTION_DAYS)
            .coerceIn(MIN_RECYCLE_BIN_RETENTION_DAYS, MAX_RECYCLE_BIN_RETENTION_DAYS)
        set(value) {
            mmkv.putInt(
                KEY_RECYCLE_BIN_RETENTION_DAYS,
                value.coerceIn(MIN_RECYCLE_BIN_RETENTION_DAYS, MAX_RECYCLE_BIN_RETENTION_DAYS)
            )
            markBackupDirty()
        }

    /** WebDAV 服务地址配置 key；该值只保存在本机，不进入备份包。 */
    private const val KEY_WEBDAV_ENDPOINT = "webdav_endpoint"

    /** WebDAV 用户名配置 key；用户名只用于连接服务，不进入备份包。 */
    private const val KEY_WEBDAV_USERNAME = "webdav_username"

    /** WebDAV 密码配置 key；只保存到独立加密 MMKV，不再读写默认 MMKV。 */
    private const val KEY_WEBDAV_PASSWORD = "webdav_password"

    /** WebDAV 远端目录配置 key；默认 `/ClipMaster/backups/`，允许用户修改。 */
    private const val KEY_WEBDAV_REMOTE_DIR = "webdav_remote_dir"

    /** WebDAV 是否允许 HTTP 配置 key；默认 false，避免明文传输剪贴内容。 */
    private const val KEY_WEBDAV_ALLOW_INSECURE_HTTP = "webdav_allow_insecure_http"

    /** 本地自动/联动备份目录授权 URI 配置 key；属于设备绑定授权，不进入备份包。 */
    private const val KEY_LOCAL_BACKUP_DIR_URI = "local_backup_dir_uri"

    /** 本地备份目录展示名配置 key；只用于 UI 展示，授权是否有效仍以 URI 为准。 */
    private const val KEY_LOCAL_BACKUP_DIR_LABEL = "local_backup_dir_label"

    /** 自动备份总开关配置 key；默认关闭，避免用户未理解明文风险时后台生成备份。 */
    private const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"

    /** 普通备份默认保留份数；本地和 WebDAV 目标都会按该值保留最近的普通备份。 */
    const val DEFAULT_BACKUP_RETENTION_COUNT = 5

    /** 普通备份最少保留份数，避免清理掉全部恢复点。 */
    const val MIN_BACKUP_RETENTION_COUNT = 1

    /** 普通备份最多保留份数，限制 WebDAV 和本地目录长期膨胀。 */
    const val MAX_BACKUP_RETENTION_COUNT = 20

    /** 普通备份保留份数配置 key。 */
    private const val KEY_BACKUP_RETENTION_COUNT = "backup_retention_count"

    /** WebDAV 自动备份是否只在 Wi-Fi 下运行；本地备份不需要网络约束。 */
    private const val KEY_AUTO_BACKUP_ONLY_WIFI = "auto_backup_only_wifi"

    /** 数据是否相对最近成功备份发生变化；dirty 为 false 时自动备份可跳过。 */
    private const val KEY_BACKUP_DIRTY = "backup_dirty"

    /** 最近一次自动备份状态 key，使用 BackupTaskStatus.name 保存本机内部枚举。 */
    private const val KEY_LAST_AUTO_BACKUP_STATUS = "last_auto_backup_status"

    /** 最近一次自动备份成功时间，单位毫秒。 */
    private const val KEY_LAST_AUTO_BACKUP_SUCCESS_AT = "last_auto_backup_success_at"

    /** 最近一次自动备份失败原因，必须是脱敏后的可行动摘要。 */
    private const val KEY_LAST_AUTO_BACKUP_FAILURE_REASON = "last_auto_backup_failure_reason"

    /** 最近一次自动备份跳过原因，和失败分开展示，避免用户误判。 */
    private const val KEY_LAST_AUTO_BACKUP_SKIP_REASON = "last_auto_backup_skip_reason"

    /** WebDAV 最近健康状态 key，来自用户主动测试连接或刷新。 */
    private const val KEY_WEBDAV_HEALTH = "webdav_health"

    /** WebDAV 最近健康检查时间，单位毫秒。 */
    private const val KEY_WEBDAV_HEALTH_CHECKED_AT = "webdav_health_checked_at"

    /** 最近一次成功自动备份摘要 JSON，不包含剪贴正文或凭据。 */
    private const val KEY_LAST_BACKUP_SUCCESS_SUMMARY = "last_backup_success_summary"

    /** WebDAV 服务根地址。 */
    var webDavEndpoint: String
        get() = mmkv.getString(KEY_WEBDAV_ENDPOINT, "") ?: ""
        set(value) {
            mmkv.putString(KEY_WEBDAV_ENDPOINT, value.trim())
        }

    /** WebDAV 用户名。 */
    var webDavUsername: String
        get() = mmkv.getString(KEY_WEBDAV_USERNAME, "") ?: ""
        set(value) {
            mmkv.putString(KEY_WEBDAV_USERNAME, value)
        }

    /** WebDAV 密码或应用专用密码；保存到加密 MMKV，不参与备份导出。 */
    var webDavPassword: String
        get() = secureMmkv.getString(KEY_WEBDAV_PASSWORD, "") ?: ""
        set(value) {
            secureMmkv.putString(KEY_WEBDAV_PASSWORD, value)
        }

    /** WebDAV 远端备份目录，保存用户原始配置，使用前由备份模块规范化。 */
    var webDavRemoteDir: String
        get() = mmkv.getString(KEY_WEBDAV_REMOTE_DIR, "/ClipMaster/backups/") ?: "/ClipMaster/backups/"
        set(value) {
            mmkv.putString(KEY_WEBDAV_REMOTE_DIR, value)
        }

    /** 是否允许 HTTP WebDAV；只建议调试或可信内网使用。 */
    var webDavAllowInsecureHttp: Boolean
        get() = mmkv.getBoolean(KEY_WEBDAV_ALLOW_INSECURE_HTTP, false)
        set(value) {
            mmkv.putBoolean(KEY_WEBDAV_ALLOW_INSECURE_HTTP, value)
        }

    /** 本地备份文件夹 SAF 授权 URI；为空表示未设置，授权失效时由备份页提示用户重新选择。 */
    var localBackupDirUri: String
        get() = mmkv.getString(KEY_LOCAL_BACKUP_DIR_URI, "") ?: ""
        set(value) {
            mmkv.putString(KEY_LOCAL_BACKUP_DIR_URI, value)
        }

    /** 本地备份文件夹展示路径或名称；不参与文件写入，清空目录 URI 时也需要同步清空。 */
    var localBackupDirLabel: String
        get() = mmkv.getString(KEY_LOCAL_BACKUP_DIR_LABEL, "") ?: ""
        set(value) {
            mmkv.putString(KEY_LOCAL_BACKUP_DIR_LABEL, value)
        }

    /** 自动备份统一开关；开启前调用方必须确认至少存在一个可用目标。 */
    var autoBackupEnabled: Boolean
        get() = mmkv.getBoolean(KEY_AUTO_BACKUP_ENABLED, false)
        set(value) {
            mmkv.putBoolean(KEY_AUTO_BACKUP_ENABLED, value)
        }

    /** 普通备份保留份数；保存和读取都裁剪到 1-20，避免异常配置参与删除。 */
    var backupRetentionCount: Int
        get() = mmkv.getInt(KEY_BACKUP_RETENTION_COUNT, DEFAULT_BACKUP_RETENTION_COUNT)
            .coerceIn(MIN_BACKUP_RETENTION_COUNT, MAX_BACKUP_RETENTION_COUNT)
        set(value) {
            mmkv.putInt(KEY_BACKUP_RETENTION_COUNT, value.coerceIn(MIN_BACKUP_RETENTION_COUNT, MAX_BACKUP_RETENTION_COUNT))
        }

    /** WebDAV 自动备份仅 Wi-Fi 约束；本地目录备份仍可在无网络时执行。 */
    var autoBackupOnlyWifi: Boolean
        get() = mmkv.getBoolean(KEY_AUTO_BACKUP_ONLY_WIFI, false)
        set(value) {
            mmkv.putBoolean(KEY_AUTO_BACKUP_ONLY_WIFI, value)
        }

    /** 数据变更标记；自动备份成功后清除，恢复完成后重新置为 true。 */
    var backupDirty: Boolean
        get() = mmkv.getBoolean(KEY_BACKUP_DIRTY, true)
        set(value) {
            mmkv.putBoolean(KEY_BACKUP_DIRTY, value)
        }

    /** 标记备份数据已变化；跨安装有意义的设置保存时使用，调度由 app 层统一处理。 */
    fun markBackupDirty() {
        backupDirty = true
    }

    /** 最近一次自动备份状态，未知枚举值回退 Idle，避免版本变更导致页面崩溃。 */
    var lastAutoBackupStatus: BackupTaskStatus
        get() = runCatching {
            BackupTaskStatus.valueOf(mmkv.getString(KEY_LAST_AUTO_BACKUP_STATUS, BackupTaskStatus.Idle.name) ?: BackupTaskStatus.Idle.name)
        }.getOrDefault(BackupTaskStatus.Idle)
        set(value) {
            mmkv.putString(KEY_LAST_AUTO_BACKUP_STATUS, value.name)
        }

    /** 最近一次自动备份成功时间，0 表示没有成功记录。 */
    var lastAutoBackupSuccessAt: Long
        get() = mmkv.getLong(KEY_LAST_AUTO_BACKUP_SUCCESS_AT, 0L)
        set(value) {
            mmkv.putLong(KEY_LAST_AUTO_BACKUP_SUCCESS_AT, value)
        }

    /** 最近一次自动备份失败原因，调用方只能写入脱敏后的可行动原因。 */
    var lastAutoBackupFailureReason: String
        get() = mmkv.getString(KEY_LAST_AUTO_BACKUP_FAILURE_REASON, "") ?: ""
        set(value) {
            mmkv.putString(KEY_LAST_AUTO_BACKUP_FAILURE_REASON, value)
        }

    /** 最近一次自动备份跳过原因，和失败原因分开保存。 */
    var lastAutoBackupSkipReason: String
        get() = mmkv.getString(KEY_LAST_AUTO_BACKUP_SKIP_REASON, "") ?: ""
        set(value) {
            mmkv.putString(KEY_LAST_AUTO_BACKUP_SKIP_REASON, value)
        }

    /** WebDAV 目标健康状态缓存，页面进入时只读缓存，不自动发网络请求。 */
    var webDavHealth: BackupTargetHealth
        get() = runCatching {
            BackupTargetHealth.valueOf(mmkv.getString(KEY_WEBDAV_HEALTH, BackupTargetHealth.Unknown.name) ?: BackupTargetHealth.Unknown.name)
        }.getOrDefault(BackupTargetHealth.Unknown)
        set(value) {
            mmkv.putString(KEY_WEBDAV_HEALTH, value.name)
        }

    /** WebDAV 最近健康检查时间，0 表示从未检查。 */
    var webDavHealthCheckedAt: Long
        get() = mmkv.getLong(KEY_WEBDAV_HEALTH_CHECKED_AT, 0L)
        set(value) {
            mmkv.putLong(KEY_WEBDAV_HEALTH_CHECKED_AT, value)
        }

    /** 最近一次自动备份成功摘要；解析失败会清空并回退为空，避免坏配置持续影响页面。 */
    var lastBackupSuccessSummary: BackupSuccessSummary?
        get() {
            val text = mmkv.getString(KEY_LAST_BACKUP_SUCCESS_SUMMARY, "")?.takeIf { it.isNotBlank() } ?: return null
            return runCatching { BackupJson.decodeSuccessSummary(text) }.getOrElse {
                mmkv.removeValueForKey(KEY_LAST_BACKUP_SUCCESS_SUMMARY)
                null
            }
        }
        set(value) {
            if (value == null) {
                mmkv.removeValueForKey(KEY_LAST_BACKUP_SUCCESS_SUMMARY)
            } else {
                mmkv.putString(KEY_LAST_BACKUP_SUCCESS_SUMMARY, BackupJson.encodeSuccessSummary(value))
            }
        }
}
