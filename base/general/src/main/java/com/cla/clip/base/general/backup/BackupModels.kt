package com.cla.clip.base.general.backup

import androidx.annotation.Keep
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 备份文件格式标识，用于恢复前快速判断用户选择的备份包是否属于本应用。 */
const val BACKUP_FORMAT = "clip_master_backup"

/** 当前备份协议版本；v2 将列表数据从 JSON 数组升级为 JSONL，降低大数据量导出和恢复的内存峰值。 */
const val BACKUP_SCHEMA_VERSION = 2

/** 第一版备份不加密，但保留协议字段，后续可以平滑扩展到密码加密格式。 */
const val BACKUP_ENCRYPTION_NONE = "none"

/** 当前备份以 zip 包承载多个 JSON 数据文件；zip 只是包格式，不代表备份包已经加密。 */
const val BACKUP_COMPRESSION_ZIP = "zip"

/** v1 备份包内列表文件格式，使用完整 JSON 数组；仅用于旧备份导入兼容。 */
const val BACKUP_DATA_FORMAT_JSON_ARRAY = "json_array"

/** v2 备份包内列表文件格式，使用一行一条记录的 JSONL。 */
const val BACKUP_DATA_FORMAT_JSONL = "jsonl"

/**
 * 备份来源。
 *
 * 该值只描述本次备份由哪里触发，不参与恢复逻辑；列表页用它区分本地/WebDAV、手动/自动。
 */
@Keep
@Serializable
enum class BackupSource {
    /** 用户在本地备份区手动导出。 */
    @SerialName("local_manual")
    LocalManual,

    /** 后续自动备份阶段由本地目录定时生成。 */
    @SerialName("local_auto")
    LocalAuto,

    /** 用户在 WebDAV 区手动上传。 */
    @SerialName("webdav_manual")
    WebDavManual,

    /** 后续自动备份阶段由 WebDAV 定时上传。 */
    @SerialName("webdav_auto")
    WebDavAuto,
}

/**
 * 备份类型。
 *
 * `source` 描述触发位置，`backupKind` 描述生命周期语义；保留清理必须依赖这个稳定字段，不能通过文件名或来源枚举猜测，
 * 避免后续新增触发入口时误删手动备份。`Safety` 仅用于识别旧版本生成的恢复前回滚文件，新流程不再创建。
 */
@Keep
@Serializable
enum class BackupKind {
    /** 用户主动创建的手动备份，默认不参与自动保留清理。 */
    @SerialName("manual")
    Manual,

    /** 后台自动创建的普通备份，按用户配置的保留份数滚动清理。 */
    @SerialName("auto")
    Auto,

    /** 旧版本恢复前自动创建的回滚点；保留枚举值只为兼容和隐藏历史文件，新流程不再生成。 */
    @SerialName("safety")
    Safety,
}

/**
 * 统一备份快照的内存聚合模型。
 *
 * 当前落盘格式不是单个 JSON，而是 zip 包里的 `manifest.json` 和多个 `data/xxx.json`；该模型用于导出后聚合、
 * 预检和恢复 mapper，字段仍按外部协议固定，方便后续需要时生成兼容摘要或测试对象。
 */
@Keep
@Serializable
data class BackupSnapshot(
    /** 固定格式标识，恢复前必须等于 `clip_master_backup`。 */
    @SerialName("format")
    val format: String = BACKUP_FORMAT,

    /** 创建备份时的应用 id，用于避免误导入其他 App 的备份包。 */
    @SerialName("application_id")
    val applicationId: String,

    /** 备份协议版本，不直接等同 Room 数据库版本。 */
    @SerialName("schema_version")
    val schemaVersion: Int = BACKUP_SCHEMA_VERSION,

    /** 加密格式；第一版固定为 `none`。 */
    @SerialName("encryption")
    val encryption: String = BACKUP_ENCRYPTION_NONE,

    /** 备份包承载格式；当前固定为 `zip`。 */
    @SerialName("compression")
    val compression: String = BACKUP_COMPRESSION_ZIP,

    /** 备份创建时间，单位毫秒；列表排序优先使用该值。 */
    @SerialName("created_at")
    val createdAt: Long,

    /** 创建备份时的应用 versionCode，用于问题排查和未来兼容判断。 */
    @SerialName("app_version_code")
    val appVersionCode: Int,

    /** 创建备份时的应用 versionName，用于预检摘要展示。 */
    @SerialName("app_version_name")
    val appVersionName: String,

    /** 脱敏安装标识，只使用 pid 前缀，不直接暴露设备名称。 */
    @SerialName("device_label")
    val deviceLabel: String,

    /** 本次备份来源，用于列表和恢复报告展示。 */
    @SerialName("source")
    val source: BackupSource,

    /** 备份类型，用于区分手动备份、自动备份，并兼容识别旧版 safety 文件。 */
    @SerialName("backup_kind")
    val backupKind: BackupKind = BackupKind.Manual,

    /** 数据文件清单汇总 SHA-256，用于判断备份包是否损坏或被截断。 */
    @SerialName("checksum")
    val checksum: String,

    /** 备份摘要，只保存数量和轻量信息，列表可读 manifest 而不下载完整备份。 */
    @SerialName("summary")
    val summary: BackupSummary,

    /** 真实业务数据区，恢复 mapper 只从这里读取业务字段。 */
    @SerialName("data")
    val data: BackupData,
)

/**
 * zip 备份包中的单个业务数据文件描述。
 *
 * 该模型被 `BackupManifest` 引用，所以放在 manifest 前面，降低 KSP/序列化插件处理外部协议模型时的前向引用复杂度。
 */
@Keep
@Serializable
data class BackupPackageFile(
    /** zip 内相对路径，例如 `data/clips.json`，不允许绝对路径或 `..`。 */
    @SerialName("path")
    val path: String,

    /** UTF-8 内容字节数，用于发现截断或服务端异常改写。 */
    @SerialName("size")
    val size: Long,

    /** 文件内容 SHA-256 十六进制摘要。 */
    @SerialName("checksum")
    val checksum: String,
)

/**
 * 备份列表 sidecar manifest。
 *
 * WebDAV 和本地备份列表只读取该小文件展示摘要；manifest 损坏不代表完整备份一定损坏，预览时仍可下载完整快照校验。
 */
@Keep
@Serializable
data class BackupManifest(
    /** 固定格式标识，和完整快照保持一致。 */
    @SerialName("format")
    val format: String = BACKUP_FORMAT,

    /** 创建备份时的应用 id。 */
    @SerialName("application_id")
    val applicationId: String,

    /** 备份协议版本。 */
    @SerialName("schema_version")
    val schemaVersion: Int,

    /** 加密格式；当前固定为 `none`。 */
    @SerialName("encryption")
    val encryption: String = BACKUP_ENCRYPTION_NONE,

    /** 备份包承载格式；当前固定为 `zip`。 */
    @SerialName("compression")
    val compression: String = BACKUP_COMPRESSION_ZIP,

    /** 备份创建时间，单位毫秒。 */
    @SerialName("created_at")
    val createdAt: Long,

    /** 创建备份时的应用 versionCode。 */
    @SerialName("app_version_code")
    val appVersionCode: Int,

    /** 创建备份时的应用 versionName。 */
    @SerialName("app_version_name")
    val appVersionName: String,

    /** 脱敏安装标识。 */
    @SerialName("device_label")
    val deviceLabel: String,

    /** 本次备份来源。 */
    @SerialName("source")
    val source: BackupSource,

    /** 备份类型，列表展示和保留清理必须使用它，不从文件名反推。 */
    @SerialName("backup_kind")
    val backupKind: BackupKind = BackupKind.Manual,

    /** 完整快照文件名，用于 WebDAV 列表点击后定位真实备份。 */
    @SerialName("snapshot_file_name")
    val snapshotFileName: String,

    /** 完整备份包字节大小；包内 manifest 写入时可能为 0，sidecar manifest 必须写真实大小。 */
    @SerialName("file_size")
    val fileSize: Long = 0,

    /** 数据文件清单汇总 checksum。 */
    @SerialName("checksum")
    val checksum: String,

    /** 包内业务数据文件清单；恢复时必须逐个校验大小和 SHA-256。 */
    @SerialName("files")
    val files: List<BackupPackageFile> = emptyList(),

    /** 包内列表数据格式；v1 旧备份缺失时按 JSON 数组兼容读取。 */
    @SerialName("data_format")
    val dataFormat: String = BACKUP_DATA_FORMAT_JSON_ARRAY,

    /** 轻量数量摘要。 */
    @SerialName("summary")
    val summary: BackupSummary,
)

/** 备份摘要，只保存用户预检和列表展示所需的数量，不包含用户内容。 */
@Keep
@Serializable
data class BackupSummary(
    /** 剪贴记录数量，包含普通、折叠和回收站记录。 */
    @SerialName("clip_count")
    val clipCount: Int = 0,

    /** 来源 App 缓存数量。 */
    @SerialName("source_app_count")
    val sourceAppCount: Int = 0,

    /** 链接预览缓存数量。 */
    @SerialName("link_preview_count")
    val linkPreviewCount: Int = 0,

    /** 搜索历史数量。 */
    @SerialName("search_history_count")
    val searchHistoryCount: Int = 0,

    /** 视频下载记录数量。 */
    @SerialName("video_download_count")
    val videoDownloadCount: Int = 0,

    /** 图片下载批次数量。 */
    @SerialName("image_batch_count")
    val imageBatchCount: Int = 0,

    /** 图片项数量。 */
    @SerialName("image_item_count")
    val imageItemCount: Int = 0,
)

/**
 * 备份数据区。
 *
 * 各列表字段都给默认空集合，确保未来旧备份缺字段时仍可解析；恢复时只读取白名单字段。
 */
@Keep
@Serializable
data class BackupData(
    /** 剪贴记录备份。 */
    @SerialName("clips")
    val clips: List<BackupClip> = emptyList(),

    /** 来源 App 缓存备份。 */
    @SerialName("source_apps")
    val sourceApps: List<BackupSourceApp> = emptyList(),

    /** 链接预览缓存备份。 */
    @SerialName("link_previews")
    val linkPreviews: List<BackupLinkPreview> = emptyList(),

    /** 搜索历史备份。 */
    @SerialName("search_histories")
    val searchHistories: List<BackupSearchHistory> = emptyList(),

    /** 跨安装有意义的用户偏好。 */
    @SerialName("settings")
    val settings: BackupSettings = BackupSettings(),

    /** 视频下载记录元数据备份。 */
    @SerialName("video_downloads")
    val videoDownloads: List<BackupVideoDownload> = emptyList(),

    /** 图片下载批次备份。 */
    @SerialName("image_batches")
    val imageBatches: List<BackupImageBatch> = emptyList(),

    /** 图片项备份。 */
    @SerialName("image_items")
    val imageItems: List<BackupImageItem> = emptyList(),
)

/** 剪贴记录备份字段，保留用户可见状态，不导出可重新计算的派生字段作为恢复依据。 */
@Keep
@Serializable
data class BackupClip(
    @SerialName("id") val id: Long,
    @SerialName("content") val content: String,
    @SerialName("timestamp") val timestamp: Long,
    @SerialName("pinned_time") val pinnedTime: Long = 0,
    @SerialName("is_folded") val isFolded: Boolean = false,
    @SerialName("folded_at") val foldedAt: Long = 0,
    @SerialName("deleted_at") val deletedAt: Long = 0,
    @SerialName("link") val link: String? = null,
    @SerialName("source_app_package") val sourceAppPackage: String? = null,
)

/** 来源 App 备份字段；图标路径只是 hint，卸载重装后可能不可读。 */
@Keep
@Serializable
data class BackupSourceApp(
    @SerialName("package_name") val packageName: String,
    @SerialName("app_name") val appName: String,
    @SerialName("icon_path") val iconPath: String? = null,
    @SerialName("primary_color") val primaryColor: Int? = null,
    @SerialName("icon_hash") val iconHash: String? = null,
)

/** 链接预览备份字段，以 link 为稳定主键恢复。 */
@Keep
@Serializable
data class BackupLinkPreview(
    @SerialName("link") val link: String,
    @SerialName("title") val title: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("site_name") val siteName: String? = null,
)

/** 搜索历史备份字段，以范围和规范化关键词唯一索引恢复。 */
@Keep
@Serializable
data class BackupSearchHistory(
    @SerialName("id") val id: Long,
    @SerialName("query") val query: String,
    @SerialName("normalized_query") val normalizedQuery: String,
    @SerialName("is_folded") val isFolded: Boolean,
    @SerialName("updated_at") val updatedAt: Long,
)

/** 跨安装有意义的用户设置；凭据、目录授权和健康状态不进入该模型。 */
@Keep
@Serializable
data class BackupSettings(
    @SerialName("clip_item_quick_action") val clipItemQuickAction: String? = null,
    @SerialName("recycle_bin_retention_days") val recycleBinRetentionDays: Int? = null,
)

/** 视频下载记录备份字段；敏感请求头和 pending 输出不会进入备份包。 */
@Keep
@Serializable
data class BackupVideoDownload(
    @SerialName("id") val id: Long,
    @SerialName("video_url") val videoUrl: String,
    @SerialName("progress") val progress: Int = 0,
    @SerialName("status") val status: String,
    @SerialName("error_msg") val errorMsg: String? = null,
    @SerialName("save_path_hint") val savePathHint: String? = null,
    @SerialName("total_size") val totalSize: Long = 0,
    @SerialName("downloaded_size") val downloadedSize: Long = 0,
    @SerialName("create_time") val createTime: Long,
    @SerialName("update_time") val updateTime: Long,
    @SerialName("file_name") val fileName: String,
)

/** 图片下载批次备份字段；输出目录只作为重新定位 hint。 */
@Keep
@Serializable
data class BackupImageBatch(
    @SerialName("id") val id: Long,
    @SerialName("page_url") val pageUrl: String,
    @SerialName("page_name") val pageName: String,
    @SerialName("status") val status: String,
    @SerialName("total_count") val totalCount: Int,
    @SerialName("success_count") val successCount: Int = 0,
    @SerialName("failed_count") val failedCount: Int = 0,
    @SerialName("filtered_count") val filteredCount: Int = 0,
    @SerialName("output_dir_hint") val outputDirHint: String? = null,
    @SerialName("error_msg") val errorMsg: String? = null,
    @SerialName("create_time") val createTime: Long,
    @SerialName("update_time") val updateTime: Long,
)

/** 图片项备份字段；Cookie、临时下载路径和其它登录态不进入备份包。 */
@Keep
@Serializable
data class BackupImageItem(
    @SerialName("id") val id: Long,
    @SerialName("batch_id") val batchId: Long,
    @SerialName("url") val url: String,
    @SerialName("referer") val referer: String? = null,
    @SerialName("user_agent") val userAgent: String? = null,
    @SerialName("display_order") val displayOrder: Int,
    @SerialName("width") val width: Int? = null,
    @SerialName("height") val height: Int? = null,
    @SerialName("status") val status: String,
    @SerialName("output_uri_hint") val outputUriHint: String? = null,
    @SerialName("final_name") val finalName: String? = null,
    @SerialName("error_msg") val errorMsg: String? = null,
)

/**
 * 恢复预检摘要。
 *
 * 页面只展示这些脱敏统计信息，不展示剪贴内容，避免用户在公共场景误露敏感文本。
 */
data class BackupPreview(
    /** 备份创建时间。 */
    val createdAt: Long,
    /** 创建备份的应用版本名。 */
    val appVersionName: String,
    /** 备份协议版本。 */
    val schemaVersion: Int,
    /** 脱敏安装标识。 */
    val deviceLabel: String,
    /** 备份类型，用于区分手动备份、自动备份以及兼容识别旧版回滚文件。 */
    val backupKind: BackupKind,
    /** 数据区 checksum 是否通过。 */
    val checksumValid: Boolean,
    /** 数量摘要。 */
    val summary: BackupSummary,
)

/** 恢复结果报告，帮助用户理解本次恢复具体写入了什么。 */
data class BackupRestoreReport(
    /** 新增记录数量。 */
    val insertedCount: Int,
    /** 更新记录数量。 */
    val updatedCount: Int,
    /** 因本地较新或重复而跳过的记录数量。 */
    val skippedCount: Int,
    /** 按数据类别统计的恢复报告，用于大数据恢复后判断各类数据是否完整写入。 */
    val categoryReports: List<BackupRestoreCategoryReport> = emptyList(),
    /** 用户可读但不含敏感内容的提示列表。 */
    val warnings: List<String> = emptyList(),
)

/** 单个备份数据类别的恢复统计。 */
data class BackupRestoreCategoryReport(
    /** 类别稳定 code，用于日志和 UI 后续映射，不包含用户内容。 */
    val category: BackupProgressCategory,
    /** 新增记录数量。 */
    val insertedCount: Int,
    /** 更新记录数量。 */
    val updatedCount: Int,
    /** 因本地较新或重复而跳过的记录数量。 */
    val skippedCount: Int,
)

/**
 * 备份长任务状态。
 *
 * 页面、Worker 和最近状态持久化都使用同一组状态，避免 UI 通过字符串拼接推断运行结果。
 */
enum class BackupTaskStatus {
    /** 没有任务或尚未产生结果。 */
    Idle,
    /** 当前正在执行备份或恢复。 */
    Running,
    /** 所有目标都成功。 */
    Success,
    /** 至少一个目标成功，同时存在另一个目标失败。 */
    PartialSuccess,
    /** 因 dirty=false、无目标或约束未满足而跳过。 */
    Skipped,
    /** 可重试失败已交给 WorkManager 退避。 */
    RetryScheduled,
    /** 不可自动恢复或最终失败。 */
    Failed,
}

/** 最近一次自动备份摘要，保存到本机配置用于备份页无需刷新远端列表也能展示可用恢复点。 */
@Keep
@Serializable
data class BackupSuccessSummary(
    /** 最近成功备份时间，单位毫秒。 */
    @SerialName("created_at")
    val createdAt: Long,
    /** 备份文件名。 */
    @SerialName("file_name")
    val fileName: String,
    /** 备份包大小，单位字节。 */
    @SerialName("file_size")
    val fileSize: Long,
    /** 本次备份来源。 */
    @SerialName("source")
    val source: BackupSource,
    /** 备份类型；自动备份摘要通常为 auto。 */
    @SerialName("backup_kind")
    val backupKind: BackupKind,
    /** 数量摘要，不包含用户原文。 */
    @SerialName("summary")
    val summary: BackupSummary,
    /** 本地目标是否成功。 */
    @SerialName("local_success")
    val localSuccess: Boolean,
    /** WebDAV 目标是否成功。 */
    @SerialName("webdav_success")
    val webDavSuccess: Boolean,
    /** 本地保留清理数量。 */
    @SerialName("local_retention_deleted")
    val localRetentionDeleted: Int = 0,
    /** WebDAV 保留清理数量。 */
    @SerialName("webdav_retention_deleted")
    val webDavRetentionDeleted: Int = 0,
)

/** 保留份数清理结果，只记录删除数量和类型，不记录文件内容或用户数据。 */
data class BackupRetentionCleanupResult(
    /** 删除的备份数量，zip 和 manifest 配对按一份计算。 */
    val deletedCount: Int,
    /** 本次删除对象的备份类型集合，用于最近备份摘要展示和排查。 */
    val deletedKinds: List<BackupKind> = emptyList(),
)

/**
 * 备份/恢复阶段。
 *
 * 阶段 code 只描述任务进度，不包含路径、账号、剪贴内容或下载 URL，可安全用于 UI 状态和日志。
 */
enum class BackupProgressPhase {
    /** 正在准备目录、临时文件或读取配置。 */
    Preparing,
    /** 正在分页导出数据库和设置。 */
    Exporting,
    /** 正在组装 zip 备份包。 */
    Packaging,
    /** 正在写入本地文件或 SAF 目录。 */
    WritingLocal,
    /** 正在上传 WebDAV。 */
    UploadingWebDav,
    /** 正在下载 WebDAV 或复制外部 URI。 */
    Downloading,
    /** 正在校验 manifest、checksum 和 App 身份。 */
    Verifying,
    /** 正在恢复写库。 */
    Restoring,
    /** 当前任务已完成。 */
    Completed,
}

/**
 * 备份数据类别。
 *
 * 类别用于进度、日志和恢复报告；显示文案由 UI 层根据资源映射，避免底层持有页面文案。
 */
enum class BackupProgressCategory {
    /** 全局或无法归入单表的阶段。 */
    Overall,
    /** 剪贴记录。 */
    Clips,
    /** 来源 App 缓存。 */
    SourceApps,
    /** 链接预览缓存。 */
    LinkPreviews,
    /** 搜索历史。 */
    SearchHistories,
    /** 用户设置。 */
    Settings,
    /** 视频下载记录。 */
    VideoDownloads,
    /** 图片下载批次。 */
    ImageBatches,
    /** 图片下载项。 */
    ImageItems,
}

/** 统一备份进度模型，供 Repository、Worker、页面和日志复用。 */
data class BackupProgress(
    /** 当前任务 id，仅用于串联日志和 UI 状态，不进入备份协议。 */
    val taskId: String,
    /** 当前阶段。 */
    val phase: BackupProgressPhase,
    /** 当前处理的数据类别。 */
    val category: BackupProgressCategory = BackupProgressCategory.Overall,
    /** 当前阶段已处理条数。 */
    val processedCount: Long = 0,
    /** 当前文件大小，未知时为 0。 */
    val fileSize: Long = 0,
    /** 用户可见文案资源 id；底层不强依赖具体字符串，默认由 UI 根据阶段兜底。 */
    val messageRes: Int? = null,
)

/** WebDAV 目标最近一次健康检查状态，仅缓存脱敏状态，不保存密码或完整 URL。 */
enum class BackupTargetHealth {
    /** 尚未检查或用户清空了配置。 */
    Unknown,
    /** 最近一次主动测试连接成功。 */
    Available,
    /** 最近一次主动测试连接失败。 */
    Unavailable,
}

/**
 * 备份失败类型。
 *
 * 使用 sealed class 让 UI 可以把失败转成可行动提示：改密码、换目录、重新选文件或升级应用。
 */
sealed class BackupFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 用户选择的文件不是剪贴板助手备份。 */
    class InvalidFormat : BackupFailure("invalid_format")

    /** 备份属于其它 applicationId。 */
    class AppMismatch : BackupFailure("app_mismatch")

    /** 当前版本不支持该备份协议。 */
    class UnsupportedSchema : BackupFailure("unsupported_schema")

    /** checksum 校验失败，文件可能损坏或被截断。 */
    class ChecksumMismatch : BackupFailure("checksum_mismatch")

    /** WebDAV 路径不可写或本地 URI 不可写。 */
    class StorageNotWritable(cause: Throwable? = null) : BackupFailure("storage_not_writable", cause)

    /** WebDAV 认证失败。 */
    class AuthenticationFailed : BackupFailure("authentication_failed")

    /** 网络或服务器行为不符合预期。 */
    class RemoteFailed(cause: Throwable? = null) : BackupFailure("remote_failed", cause)

    /** 备份包超过当前内存式导出的保护上限。 */
    class FileTooLarge : BackupFailure("file_too_large")

    /** 解析 zip 包或包内 JSON 失败。 */
    class ParseFailed(cause: Throwable? = null) : BackupFailure("parse_failed", cause)

    /** 备份临时文件已经被清理或不可读，需要用户重新选择文件。 */
    class TempFileUnavailable(cause: Throwable? = null) : BackupFailure("temp_file_unavailable", cause)

    /** 私有临时目录空间不足，无法安全生成或复制备份包。 */
    class InsufficientSpace(cause: Throwable? = null) : BackupFailure("insufficient_space", cause)
}
