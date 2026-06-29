package com.cla.clip.master.installedapps

/** 安装应用名称最大长度；只影响过滤页展示，保存规则仍只看完整包名。 */
internal const val MAX_INSTALLED_APP_NAME_LENGTH = 80

/** 包可见性或权限被系统拒绝时的低敏原因码。 */
internal const val INSTALLED_APP_REASON_PACKAGE_VISIBILITY_DENIED = "package_visibility_denied"

/** PackageManager 扫描出现普通异常时的低敏原因码。 */
internal const val INSTALLED_APP_REASON_PACKAGE_SCAN_FAILED = "package_scan_failed"

/** PackageManager 扫描成功但排除当前 App 后没有候选时的低敏原因码。 */
internal const val INSTALLED_APP_REASON_PACKAGE_SCAN_EMPTY = "package_scan_empty"

/** 过滤页展示用的当前安装应用信息；不携带 Drawable、图标文件或可持久化状态。 */
data class InstalledAppInfo(
    /** 应用包名，作为过滤名单保存和匹配的唯一身份。 */
    val packageName: String,
    /** 用户可见应用名；空标签会在读取阶段回退为包名。 */
    val appName: String,
    /** 是否系统应用；默认列表隐藏系统应用，搜索或开关可展示。 */
    val isSystemApp: Boolean,
    /** 是否拥有 Launcher 入口；默认列表优先展示可启动非系统应用。 */
    val isLaunchableApp: Boolean,
)

/** 主进程安装应用读取结果；状态只服务当前页面会话，不进入数据库或备份。 */
sealed interface InstalledAppLoadResult {

    /** PackageManager 成功返回安装应用列表。 */
    data class Success(
        /** 已按展示规则规范化和排序的安装应用列表。 */
        val apps: List<InstalledAppInfo>,
        /** 本次读取耗时毫秒；只用于低敏日志和调试。 */
        val elapsedMs: Long,
        /** 本次读取完成时间；只保存在 ViewModel 内存态中。 */
        val loadedAtMillis: Long,
    ) : InstalledAppLoadResult

    /** PackageManager 读取失败或读取后为空。 */
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

    /** 读取当前用户可见安装应用列表，并返回规范化后的页面候选元数据。 */
    suspend fun loadInstalledApps(): InstalledAppLoadResult
}

/** PackageManager 原始应用条目；用于把 Android API 读取和纯规则规范化解耦。 */
internal data class InstalledAppPackageEntry(
    /** PackageManager 返回的应用包名；空白包名会被过滤。 */
    val packageName: String,
    /** PackageManager 返回的应用标签；空白标签会回退包名。 */
    val label: String?,
    /** ApplicationInfo flags 计算出的系统应用标记。 */
    val isSystemApp: Boolean,
)

/** PackageManager 适配接口；单测用假实现验证规则，不依赖 Android 框架对象。 */
internal interface InstalledAppPackageReader {

    /** 一次性读取当前用户可见的安装应用列表。 */
    fun loadInstalledApplications(): List<InstalledAppPackageEntry>

    /** 一次性读取拥有 Launcher 入口的包名集合，避免逐个包名查询启动 Intent。 */
    fun loadLauncherPackageNames(): Set<String>
}
