package com.cla.clip.master.installedapps

/** 包可见性或权限被系统拒绝时的低敏原因码。 */
internal const val INSTALLED_APP_REASON_PACKAGE_VISIBILITY_DENIED = "package_visibility_denied"

/** PackageManager 扫描出现普通异常时的低敏原因码。 */
internal const val INSTALLED_APP_REASON_PACKAGE_SCAN_FAILED = "package_scan_failed"

/** 过滤页当前安装信息；仅含身份、名称和系统标记，图标由可见 UI 加载，不参与持久化或外部协议。 */
data class InstalledAppInfo(
    /** 应用包名，作为过滤名单保存和匹配的唯一身份。 */
    val packageName: String,
    /** 当前扫描的完整应用名；原样保留空白，仅缺失标签转为空串，不使用包名或历史名称替代。 */
    val appName: String,
    /** 是否系统应用；默认列表隐藏系统应用，仅打开系统应用开关后展示。 */
    val isSystemApp: Boolean,
)

/** 主进程安装应用读取结果；状态只服务当前页面会话，不进入数据库或备份。 */
sealed interface InstalledAppLoadResult {

    /** PackageManager 正常完成读取，允许范围过滤后的结果为空。 */
    data class Success(
        /** 已规范化和排序的安装应用列表；空列表表示成功零结果，不表示读取失败。 */
        val apps: List<InstalledAppInfo>,
        /** 本次读取耗时毫秒；只用于低敏日志和调试。 */
        val elapsedMs: Long,
        /** 本次读取完成时间；只保存在 ViewModel 内存态中。 */
        val loadedAtMillis: Long,
    ) : InstalledAppLoadResult

    /** PackageManager 因异常读取失败；正常空列表必须使用 Success。 */
    data class Failed(
        /** 低敏失败原因码；UI 只展示本地读取失败语义。 */
        val reasonCode: String,
        /** 本次读取耗时毫秒；只用于低敏日志和调试。 */
        val elapsedMs: Long,
        /** 本次读取失败时间；只保存在 ViewModel 内存态中。 */
        val loadedAtMillis: Long,
    ) : InstalledAppLoadResult
}

/** 安装应用读取入口；实现必须在 IO 调度器上访问 PackageManager。 */
interface InstalledAppReader {

    /** 在 IO 按范围读取名称与系统标记；正常零结果返回 Success，异常返回 Failed，取消直接传播。 */
    suspend fun loadInstalledApps(showSystemApps: Boolean = false): InstalledAppLoadResult
}

/** PackageManager 原始应用条目；用于把 Android API 读取和纯规则规范化解耦。 */
internal data class InstalledAppPackageEntry(
    /** PackageManager 返回的应用包名；空白包名会被过滤。 */
    val packageName: String,
    /** PackageManager 返回的原始应用标签；不 trim 或截断，缺失时才转为空名。 */
    val label: String?,
    /** ApplicationInfo flags 计算出的系统应用标记。 */
    val isSystemApp: Boolean,
)

/** PackageManager 适配接口；单测用假实现验证规则，不依赖 Android 框架对象。 */
internal interface InstalledAppPackageReader {

    /** 先排除自身及范围外系统应用，再读取保留应用的名称与系统标记。 */
    suspend fun loadInstalledApplications(showSystemApps: Boolean, selfPackageName: String): List<InstalledAppPackageEntry>
}
