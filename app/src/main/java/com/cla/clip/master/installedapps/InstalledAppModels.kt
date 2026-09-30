package com.cla.clip.master.installedapps

import android.graphics.drawable.Drawable

/** 包可见性或权限被系统拒绝时的低敏原因码。 */
internal const val INSTALLED_APP_REASON_PACKAGE_VISIBILITY_DENIED = "package_visibility_denied"

/** PackageManager 扫描出现普通异常时的低敏原因码。 */
internal const val INSTALLED_APP_REASON_PACKAGE_SCAN_FAILED = "package_scan_failed"

/** PackageManager 扫描成功但排除当前 App 后没有候选时的低敏原因码。 */
internal const val INSTALLED_APP_REASON_PACKAGE_SCAN_EMPTY = "package_scan_empty"

/** 过滤页当前安装信息；图标只保存在页面会话内存，不参与持久化或外部协议。 */
data class InstalledAppInfo(
    /** 应用包名，作为过滤名单保存和匹配的唯一身份。 */
    val packageName: String,
    /** 当前扫描的完整应用名；原样保留空白，仅缺失标签转为空串，不使用包名或历史名称替代。 */
    val appName: String,
    /** 是否系统应用；默认列表隐藏系统应用，仅打开系统应用开关后展示。 */
    val isSystemApp: Boolean,
    /** 扫描阶段加载的 Application 图标；读取失败为 null，由页面展示默认图标。 */
    val icon: Drawable? = null,
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

    /** 按系统应用开关读取名称和图标；默认排除系统应用，整个过程在 IO 调度器执行。 */
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
    /** 当前安装应用的内存图标；读取失败不影响应用条目，使用 null 表示不可用。 */
    val icon: Drawable? = null,
)

/** PackageManager 适配接口；单测用假实现验证规则，不依赖 Android 框架对象。 */
internal interface InstalledAppPackageReader {

    /** 先排除自身及范围外系统应用，再读取保留应用的名称与图标。 */
    suspend fun loadInstalledApplications(showSystemApps: Boolean, selfPackageName: String): List<InstalledAppPackageEntry>
}
