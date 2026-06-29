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
     * app 安装之后生成的本机随机标识，只要 app 不被卸载就不会变化。
     *
     * 该值只用于本机安装身份、Shizuku 进程名和备份设备标签；当前开发阶段不兼容旧 UUID 格式，后续上线前如需迁移再补方案。
     */
    private const val KEY_PID = "pid"

    /** 当前安装实例的纯数字随机 ID，不包含用户身份信息；卸载重装后会变化。 */
    val pid: String
        get() {
            /** 持久化的安装 ID；旧 UUID 或异常值会在开发阶段直接重建。 */
            val storedValue = mmkv.getString(KEY_PID, null)
            if (!NumericInstallIdGenerator.isValid(storedValue)) {
                logE(TAG) { "创建纯数字 pid" }
                /** 新安装级 ID；保持 String 形式，避免前导 0 丢失。 */
                val numericId = NumericInstallIdGenerator.generate()
                mmkv.putString(KEY_PID, numericId)
                return numericId
            }

            /** 已校验通过的安装 ID；`isValid` 保证这里不会为空。 */
            return storedValue.orEmpty()
        }


    /**
     * Shizuku 进程完整名称。
     *
     * 用来在 Shizuku 进程启动之后，检查当前是否为最新 Shizuku 进程，清理掉旧 Shizuku 进程。
     */
    private const val KEY_SHIZUKU_SUFFIX = "current_suffix"

    /** 当前有效的 Shizuku 完整进程名；只由 app 侧刷新，Shizuku 侧通过 Provider 只读查询。 */
    var shizukuSuffix: String
        get() = mmkv.getString(KEY_SHIZUKU_SUFFIX, null)?.takeIf { it.isNotBlank() } ?: ""
        set(value) {
            /** 待保存的完整进程名；空白值只用于清理异常状态，正常路径应传入 applicationId:suffix。 */
            val normalizedProcessName = value.trim()
            // current suffix 只由 App 进程写入。Shizuku 进程只能通过 Provider/callback 读取，不能反写，避免旧 Shizuku 进程把新值覆盖回旧值。
            mmkv.putString(KEY_SHIZUKU_SUFFIX, normalizedProcessName)
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

    /** 来源 App 剪贴保存过滤名单 key；值为换行分隔包名，包名本身会拒绝换行等危险字符。 */
    private const val KEY_BLOCKED_CLIP_SOURCE_PACKAGES = "blocked_clip_source_packages"

    /** 来源过滤名单状态流；设置页、详情页和保存链路通过它拿到同一份规则快照。 */
    private val _blockedClipSourcePackagesFlow by lazy {
        MutableStateFlow(blockedClipSourcePackages)
    }

    /** 来源 App 剪贴保存过滤名单流；只包含规范化后的包名集合，不包含 App 名称或图标。 */
    val blockedClipSourcePackagesFlow: StateFlow<Set<String>>
        get() = _blockedClipSourcePackagesFlow.asStateFlow()

    /** 来源 App 剪贴保存过滤名单；读取时也会规范化，兼容旧值或异常写入。 */
    val blockedClipSourcePackages: Set<String>
        get() = decodeBlockedClipSourcePackages(mmkv.getString(KEY_BLOCKED_CLIP_SOURCE_PACKAGES, null))

    /**
     * 原子加入一个来源包名。
     *
     * 返回 true 表示名单发生变化；重复、非法或超过上限时返回 false，调用方可据此决定是否提示用户。
     */
    @Synchronized
    fun addBlockedPackage(packageName: String): Boolean {
        /** 待加入的规范化包名；无效输入不会写入 MMKV。 */
        val normalizedPackageName = ClipSourceBlockRules.normalizeSinglePackage(packageName) ?: return false
        /** 当前名单快照；在同步块中读取，避免连续 add/remove 时互相覆盖。 */
        val currentPackages = blockedClipSourcePackages
        if (normalizedPackageName in currentPackages || currentPackages.size >= ClipSourceBlockRules.MAX_PACKAGE_COUNT) {
            return false
        }
        return writeBlockedClipSourcePackages(currentPackages + normalizedPackageName)
    }

    /**
     * 原子移除一个来源包名。
     *
     * 返回 true 表示名单发生变化；未知或非法包名返回 false。
     */
    @Synchronized
    fun removeBlockedPackage(packageName: String): Boolean {
        /** 待移除的规范化包名；无效输入不影响当前名单。 */
        val normalizedPackageName = ClipSourceBlockRules.normalizeSinglePackage(packageName) ?: return false
        /** 当前名单快照；移除后仍走统一写入，确保排序和 Flow 同步。 */
        val currentPackages = blockedClipSourcePackages
        if (normalizedPackageName !in currentPackages) {
            return false
        }
        return writeBlockedClipSourcePackages(currentPackages - normalizedPackageName)
    }

    /**
     * 原子替换来源过滤名单。
     *
     * 输入会按统一规则 trim、去空、去重、排序并裁剪到上限；用于设置页确认和备份恢复并集落盘。
     */
    @Synchronized
    fun replaceBlockedPackages(packages: Iterable<String?>): Boolean {
        /** 规范化后的目标名单；与当前一致时不重复写入，减少不必要的备份 dirty。 */
        val normalizedPackages = ClipSourceBlockRules.normalizePackageSet(packages)
        if (normalizedPackages == blockedClipSourcePackages) {
            return false
        }
        return writeBlockedClipSourcePackages(normalizedPackages)
    }

    /** 将换行分隔的持久化字符串恢复为规范化包名集合。 */
    private fun decodeBlockedClipSourcePackages(value: String?): Set<String> {
        /** 按行拆开的原始包名列表；空值恢复为空集合。 */
        val rawPackages = value
            ?.lineSequence()
            ?.toList()
            .orEmpty()
        return ClipSourceBlockRules.normalizePackageSet(rawPackages)
    }

    /** 写入规范化后的过滤名单，并同步 Flow 与备份 dirty。 */
    private fun writeBlockedClipSourcePackages(packages: Iterable<String?>): Boolean {
        /** 最终落盘名单；这里再次规范化，保证所有调用路径遵守同一规则。 */
        val normalizedPackages = ClipSourceBlockRules.normalizePackageSet(packages)
        mmkv.putString(KEY_BLOCKED_CLIP_SOURCE_PACKAGES, normalizedPackages.joinToString(separator = "\n"))
        _blockedClipSourcePackagesFlow.value = normalizedPackages
        markBackupDirty()
        return true
    }

    /** App 自升级自动检查的 24 小时限频窗口。 */
    const val APP_UPDATE_AUTO_CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L

    /** 上次 App 自升级检查时间 key；只用于本机限频，不进入备份包。 */
    private const val KEY_APP_UPDATE_LAST_CHECK_AT = "app_update_last_check_at"

    /** 上次 App 自升级轻量检查时间；卸载重装后重新检查即可，不触发备份 dirty。 */
    var appUpdateLastCheckAt: Long
        get() = mmkv.getLong(KEY_APP_UPDATE_LAST_CHECK_AT, 0L)
        set(value) {
            mmkv.putLong(KEY_APP_UPDATE_LAST_CHECK_AT, value.coerceAtLeast(0L))
        }

    /** 广告源自动选择模式，具体含义由广告 API 模块的 `AdSourceSelectionMode.AUTO` 保持一致。 */
    const val DEFAULT_ACTIVE_AD_SOURCE_ID = "auto"

    /** 广告总开关默认值；第一版默认开启，由广告源集合、隐私状态和调试模块共同决定是否真正展示。 */
    const val DEFAULT_ADS_GLOBAL_ENABLED = true

    /** 默认广告隐私同意状态；未接入正式隐私流程前真实 SDK 必须保持不可用。 */
    const val DEFAULT_AD_CONSENT_STATE = "unknown"

    /** 默认隐私政策版本；空字符串表示当前没有可验证的广告 SDK 隐私版本。 */
    const val DEFAULT_AD_PRIVACY_POLICY_VERSION = ""

    /** 当前广告源选择配置 key；值为 `auto`、`off` 或稳定 sourceId，不进入备份包。 */
    private const val KEY_ACTIVE_AD_SOURCE_ID = "active_ad_source_id"

    /** 广告总开关配置 key；只影响本机广告展示，不代表用户可恢复数据。 */
    private const val KEY_ADS_GLOBAL_ENABLED = "ads_global_enabled"

    /** 广告隐私同意状态配置 key；属于本机合规运行态，不进入备份包。 */
    private const val KEY_AD_CONSENT_STATE = "ad_consent_state"

    /** 用户同意的广告隐私版本配置 key；用于防止旧隐私版本绕过新增 SDK 清单。 */
    private const val KEY_AD_PRIVACY_POLICY_VERSION = "ad_privacy_policy_version"

    /** 当前广告源选择状态流；宿主收集后可在运行时切换已内置广告源。 */
    private val _activeAdSourceIdFlow by lazy {
        MutableStateFlow(activeAdSourceId)
    }

    /** 广告总开关状态流；关闭时宿主隐藏所有广告位。 */
    private val _adsGlobalEnabledFlow by lazy {
        MutableStateFlow(adsGlobalEnabled)
    }

    /** 广告隐私同意状态流；真实 SDK 只在 granted 且版本匹配时可用。 */
    private val _adConsentStateFlow by lazy {
        MutableStateFlow(adConsentState)
    }

    /** 广告隐私政策版本流；版本变化后真实 SDK 需要等待用户重新同意。 */
    private val _adPrivacyPolicyVersionFlow by lazy {
        MutableStateFlow(adPrivacyPolicyVersion)
    }

    /** 当前广告源选择流；只用于运行时 UI/宿主接线刷新，不触发备份 dirty。 */
    val activeAdSourceIdFlow: StateFlow<String>
        get() = _activeAdSourceIdFlow.asStateFlow()

    /** 广告总开关流；只用于本机广告显示策略，不触发备份 dirty。 */
    val adsGlobalEnabledFlow: StateFlow<Boolean>
        get() = _adsGlobalEnabledFlow.asStateFlow()

    /** 广告隐私同意状态流；只用于广告 SDK 懒初始化判断，不触发备份 dirty。 */
    val adConsentStateFlow: StateFlow<String>
        get() = _adConsentStateFlow.asStateFlow()

    /** 广告隐私政策版本流；只用于广告 SDK 合规判断，不触发备份 dirty。 */
    val adPrivacyPolicyVersionFlow: StateFlow<String>
        get() = _adPrivacyPolicyVersionFlow.asStateFlow()

    /** 当前广告源选择；空白值保存时回退到 auto，避免异常配置让广告位永久不可用。 */
    var activeAdSourceId: String
        get() = mmkv.getString(KEY_ACTIVE_AD_SOURCE_ID, DEFAULT_ACTIVE_AD_SOURCE_ID)
            ?.trim()
            ?.ifBlank { DEFAULT_ACTIVE_AD_SOURCE_ID }
            ?: DEFAULT_ACTIVE_AD_SOURCE_ID
        set(value) {
            /** 待保存的广告源选择值；空白值不保留，统一写回 auto。 */
            val normalizedSourceId = value.trim().ifBlank { DEFAULT_ACTIVE_AD_SOURCE_ID }
            mmkv.putString(KEY_ACTIVE_AD_SOURCE_ID, normalizedSourceId)
            _activeAdSourceIdFlow.value = normalizedSourceId
        }

    /** 广告总开关；关闭后所有广告位直接隐藏，但不影响剪贴、备份、下载等核心功能。 */
    var adsGlobalEnabled: Boolean
        get() = mmkv.getBoolean(KEY_ADS_GLOBAL_ENABLED, DEFAULT_ADS_GLOBAL_ENABLED)
        set(value) {
            mmkv.putBoolean(KEY_ADS_GLOBAL_ENABLED, value)
            _adsGlobalEnabledFlow.value = value
        }

    /** 广告隐私同意状态；只接受 granted/denied/unknown/not_required 这类稳定低敏值。 */
    var adConsentState: String
        get() = normalizeAdConsentState(mmkv.getString(KEY_AD_CONSENT_STATE, DEFAULT_AD_CONSENT_STATE))
        set(value) {
            /** 规范化后的同意状态；未知或空白统一回退 unknown，避免真实 SDK 误初始化。 */
            val normalizedConsentState = normalizeAdConsentState(value)
            mmkv.putString(KEY_AD_CONSENT_STATE, normalizedConsentState)
            _adConsentStateFlow.value = normalizedConsentState
        }

    /** 用户最近同意的广告隐私版本；空字符串表示当前没有可验证版本。 */
    var adPrivacyPolicyVersion: String
        get() = mmkv.getString(KEY_AD_PRIVACY_POLICY_VERSION, DEFAULT_AD_PRIVACY_POLICY_VERSION)
            ?.trim()
            ?: DEFAULT_AD_PRIVACY_POLICY_VERSION
        set(value) {
            /** 清理后的隐私版本，只保存低敏版本号，不保存政策正文或 URL。 */
            val normalizedVersion = value.trim()
            mmkv.putString(KEY_AD_PRIVACY_POLICY_VERSION, normalizedVersion)
            _adPrivacyPolicyVersionFlow.value = normalizedVersion
        }

    /**
     * 规范化广告同意状态。
     *
     * 该状态属于本机合规运行态，未知值必须回退 `unknown`，避免配置污染导致真实 SDK 在未同意时启动。
     */
    private fun normalizeAdConsentState(value: String?): String {
        /** 去掉首尾空白并转小写后的状态值；只允许固定白名单进入持久化和 Flow。 */
        val normalizedValue = value?.trim()?.lowercase().orEmpty()
        return when (normalizedValue) {
            "granted", "denied", "not_required" -> normalizedValue
            else -> DEFAULT_AD_CONSENT_STATE
        }
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
            /** 去掉首尾空白后的地址，避免用户粘贴时多出不可见空格。 */
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
            /** MMKV 中保存的备份摘要 JSON；为空时表示当前没有最近成功摘要。 */
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
