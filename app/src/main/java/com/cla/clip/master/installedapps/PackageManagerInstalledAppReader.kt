package com.cla.clip.master.installedapps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 基于主进程 PackageManager 的安装应用读取器。
 *
 * 该实现依赖主 App 声明 QUERY_ALL_PACKAGES，只在过滤页进入或用户刷新时读取，不持久化安装列表。
 */
class PackageManagerInstalledAppReader : InstalledAppReader {

    /** Android PackageManager 适配器；隔离 API 版本差异和 Android 对象读取。 */
    private val packageReader: InstalledAppPackageReader

    /** 当前 App 包名；安装列表和手动添加都必须拒绝该包名。 */
    private val selfPackageName: String

    /** 当前墙钟时间；生产环境使用系统时间，单测通过内部构造函数注入。 */
    private val clock: () -> Long

    /** PackageManager 读取调度器；生产环境固定使用 IO。 */
    private val dispatcher: CoroutineDispatcher

    @Inject
    constructor(
        @ApplicationContext appContext: Context,
    ) : this(
        packageReader = AndroidInstalledAppPackageReader(appContext.packageManager),
        selfPackageName = appContext.packageName,
        clock = { System.currentTimeMillis() },
        dispatcher = Dispatchers.IO,
    )

    internal constructor(
        packageReader: InstalledAppPackageReader,
        selfPackageName: String,
        clock: () -> Long,
        dispatcher: CoroutineDispatcher,
    ) {
        /** Android PackageManager 适配器；单测会替换为假实现。 */
        this.packageReader = packageReader
        /** 当前 App 包名；用于排除自身。 */
        this.selfPackageName = selfPackageName
        /** 当前时间来源；用于可测试地生成 loadedAtMillis。 */
        this.clock = clock
        /** 读取调度器；用于可测试地控制协程执行。 */
        this.dispatcher = dispatcher
    }

    override suspend fun loadInstalledApps(): InstalledAppLoadResult = withContext(dispatcher) {
        /** 本次读取开始时间；只用于低敏耗时日志。 */
        val startedAtMillis = clock()
        try {
            /** Launcher 包名集合；一次性查询，避免逐个包名调用 getLaunchIntentForPackage。 */
            val launchablePackages = packageReader.loadLauncherPackageNames()
            /** 规范化后的安装应用列表；不包含当前 App，也不携带图标对象。 */
            val apps = normalizeInstalledAppEntries(
                entries = packageReader.loadInstalledApplications(),
                launcherPackages = launchablePackages,
                selfPackageName = selfPackageName,
            )
            /** 本次读取结束时间；只保存到 ViewModel 内存态，不持久化。 */
            val loadedAtMillis = clock()
            /** 本次读取耗时；clock 在单测中可固定，避免断言依赖真实时间。 */
            val elapsedMs = (loadedAtMillis - startedAtMillis).coerceAtLeast(0L)
            if (apps.isEmpty()) {
                logW(TAG) {
                    "installed_app_scan_result reasonCode=$INSTALLED_APP_REASON_PACKAGE_SCAN_EMPTY " +
                        "appCount=0 elapsedMs=$elapsedMs"
                }
                InstalledAppLoadResult.Failed(
                    reasonCode = INSTALLED_APP_REASON_PACKAGE_SCAN_EMPTY,
                    elapsedMs = elapsedMs,
                    loadedAtMillis = loadedAtMillis,
                )
            } else {
                logI(TAG) {
                    "installed_app_scan_result reasonCode=success appCount=${apps.size} " +
                        "launcherCount=${launchablePackages.size} elapsedMs=$elapsedMs"
                }
                InstalledAppLoadResult.Success(
                    apps = apps,
                    elapsedMs = elapsedMs,
                    loadedAtMillis = loadedAtMillis,
                )
            }
        } catch (error: SecurityException) {
            /** 失败时间；只用于内存态和低敏日志，不记录包列表或异常消息。 */
            val failedAtMillis = clock()
            /** 失败耗时；防止测试固定时钟时出现负值。 */
            val elapsedMs = (failedAtMillis - startedAtMillis).coerceAtLeast(0L)
            logW(TAG) {
                "installed_app_scan_result reasonCode=$INSTALLED_APP_REASON_PACKAGE_VISIBILITY_DENIED elapsedMs=$elapsedMs " +
                    "errorType=${error::class.java.simpleName}"
            }
            InstalledAppLoadResult.Failed(
                reasonCode = INSTALLED_APP_REASON_PACKAGE_VISIBILITY_DENIED,
                elapsedMs = elapsedMs,
                loadedAtMillis = failedAtMillis,
            )
        } catch (error: Throwable) {
            /** 失败时间；只用于内存态和低敏日志，不记录包列表或异常消息。 */
            val failedAtMillis = clock()
            /** 失败耗时；防止测试固定时钟时出现负值。 */
            val elapsedMs = (failedAtMillis - startedAtMillis).coerceAtLeast(0L)
            logW(TAG) {
                "installed_app_scan_result reasonCode=$INSTALLED_APP_REASON_PACKAGE_SCAN_FAILED elapsedMs=$elapsedMs " +
                    "errorType=${error::class.java.simpleName}"
            }
            InstalledAppLoadResult.Failed(
                reasonCode = INSTALLED_APP_REASON_PACKAGE_SCAN_FAILED,
                elapsedMs = elapsedMs,
                loadedAtMillis = failedAtMillis,
            )
        }
    }

    private companion object {
        /** 安装应用读取日志标签；日志禁止输出完整 App 列表或图标内容。 */
        private const val TAG = "InstalledAppReader"
    }
}

/**
 * 规范化 PackageManager 原始安装应用条目。
 *
 * 只负责纯规则：排除当前 App、裁剪名称、空标签回退包名、标记可启动状态并稳定排序。
 */
internal fun normalizeInstalledAppEntries(
    entries: List<InstalledAppPackageEntry>,
    launcherPackages: Set<String>,
    selfPackageName: String,
): List<InstalledAppInfo> {
    /** 当前 App 包名；空白时仍按空串比较，避免误删其他条目。 */
    val normalizedSelfPackageName = selfPackageName.trim()
    return entries.mapNotNull { entry ->
        /** 原始包名 trim 后作为稳定身份；空包名直接丢弃。 */
        val packageName = entry.packageName.trim().takeIf { value -> value.isNotEmpty() } ?: return@mapNotNull null
        if (packageName == normalizedSelfPackageName) {
            return@mapNotNull null
        }
        /** 规范化展示名称；空标签回退包名，超长名称裁剪到固定长度。 */
        val appName = normalizeInstalledAppName(label = entry.label, packageName = packageName)
        InstalledAppInfo(
            packageName = packageName,
            appName = appName,
            isSystemApp = entry.isSystemApp,
            isLaunchableApp = packageName in launcherPackages,
        )
    }.distinctBy { app -> app.packageName }
        .sortedWith(
            compareBy<InstalledAppInfo> { app -> app.appName.lowercase(Locale.ROOT) }
                .thenBy { app -> app.packageName }
        )
}

/** 规范化安装应用名称；空标签回退包名，超长名称只裁剪展示文本不影响包名。 */
private fun normalizeInstalledAppName(label: String?, packageName: String): String {
    /** 去除首尾空白后的应用标签；为空时回退包名。 */
    val normalizedLabel = label?.trim().takeUnless { value -> value.isNullOrEmpty() } ?: packageName
    return normalizedLabel.take(MAX_INSTALLED_APP_NAME_LENGTH)
}

/** Android PackageManager 适配器；封装 API 33 flags 差异。 */
private class AndroidInstalledAppPackageReader(
    /** 系统 PackageManager；只在 IO 调度器中访问。 */
    private val packageManager: PackageManager,
) : InstalledAppPackageReader {

    override fun loadInstalledApplications(): List<InstalledAppPackageEntry> {
        /** 当前用户可见安装应用；不使用 MATCH_DISABLED_COMPONENTS 强行展示禁用或冻结 App。 */
        val applications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }
        return applications.map { applicationInfo ->
            /** 当前应用标签；读取失败时后续会回退包名。 */
            val label = applicationInfo.loadLabel(packageManager)?.toString()
            InstalledAppPackageEntry(
                packageName = applicationInfo.packageName,
                label = label,
                isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            )
        }
    }

    override fun loadLauncherPackageNames(): Set<String> {
        /** Launcher 查询 Intent；一次性收集可启动包名，避免逐个应用查询。 */
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        /** 当前用户可见 Launcher Activity；不请求禁用组件。 */
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, 0)
        }
        return resolveInfos.mapNotNullTo(mutableSetOf()) { resolveInfo ->
            /** Launcher Activity 所属包名；异常空包名不参与可启动标记。 */
            resolveInfo.activityInfo?.packageName?.trim()?.takeIf { packageName -> packageName.isNotEmpty() }
        }
    }
}
