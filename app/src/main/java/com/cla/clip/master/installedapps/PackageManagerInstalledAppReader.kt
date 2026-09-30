package com.cla.clip.master.installedapps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale
import javax.inject.Inject

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

    /** 在 IO 调度器按所选范围读取名称和图标；取消直接传播，扫描异常映射为低敏失败结果。 */
    override suspend fun loadInstalledApps(showSystemApps: Boolean): InstalledAppLoadResult = withContext(dispatcher) {
        /** 本次读取开始时间；只用于低敏耗时日志。 */
        val startedAtMillis = clock()
        try {
            /** 已过滤并携带内存图标的应用列表；扫描范围不影响完整过滤名单。 */
            val apps = normalizeInstalledAppEntries(
                entries = packageReader.loadInstalledApplications(showSystemApps, selfPackageName),
                selfPackageName = selfPackageName,
                showSystemApps = showSystemApps,
            )

            /** 本次读取结束时间；只保存到 ViewModel 内存态，不持久化。 */
            val loadedAtMillis = clock()

            /** 本次读取耗时；clock 在单测中可固定，避免断言依赖真实时间。 */
            val elapsedMs = (loadedAtMillis - startedAtMillis).coerceAtLeast(0L)
            if (apps.isEmpty()) {
                logW(TAG) {
                    "installed_app_scan_result reasonCode=$INSTALLED_APP_REASON_PACKAGE_SCAN_EMPTY appCount=0 elapsedMs=$elapsedMs"
                }
                InstalledAppLoadResult.Failed(
                    reasonCode = INSTALLED_APP_REASON_PACKAGE_SCAN_EMPTY,
                    elapsedMs = elapsedMs,
                    loadedAtMillis = loadedAtMillis,
                )
            } else {
                logI(TAG) {
                    "安装应用读取成功 reasonCode=success appCount=${apps.size} elapsedMs=$elapsedMs showSystemApps=$showSystemApps"
                }
                InstalledAppLoadResult.Success(
                    apps = apps,
                    elapsedMs = elapsedMs,
                    loadedAtMillis = loadedAtMillis,
                )
            }
        } catch (error: CancellationException) {
            // 切换范围和页面清理属于正常取消，不转换成读取失败。
            throw error
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
 * 只负责纯规则：校验扫描范围、缺失标签转为空名并稳定排序，原样传递完整名称和已读取图标。
 */
internal fun normalizeInstalledAppEntries(
    entries: List<InstalledAppPackageEntry>,
    selfPackageName: String,
    showSystemApps: Boolean = false,
): List<InstalledAppInfo> {
    /** 当前 App 包名；空白时仍按空串比较，避免误删其他条目。 */
    val normalizedSelfPackageName = selfPackageName.trim()
    // 在本次列表排序中复用中文比较器。
    val nameCollator = Collator.getInstance(Locale.CHINA)

    return entries.mapNotNull { entry ->
        /** 原始包名 trim 后作为稳定身份；空包名直接丢弃。 */
        val packageName = entry.packageName.trim().takeIf { value -> value.isNotEmpty() } ?: return@mapNotNull null
        // 防御性复核读取范围；Android 适配器已在加载名称和图标前应用同一规则。
        if (!shouldReadInstalledApp(packageName, normalizedSelfPackageName, entry.isSystemApp, showSystemApps)) {
            return@mapNotNull null
        }
        InstalledAppInfo(
            packageName = packageName,
            // 原样保留扫描标签；仅缺失时转为空名，显示宽度和省略号由页面负责。
            appName = entry.label.orEmpty(),
            isSystemApp = entry.isSystemApp,
            icon = entry.icon,
        )
    }.distinctBy { app ->
        app.packageName
    }.sortedWith(
        compareBy<InstalledAppInfo, String>(nameCollator) { app ->
            app.appName.lowercase(Locale.ROOT)
        }.thenBy { app -> app.packageName }
    )
}

/** Android PackageManager 适配器；封装 API 33 flags 差异。 */
private class AndroidInstalledAppPackageReader(
    /** 系统 PackageManager；只在 IO 调度器中访问。 */
    private val packageManager: PackageManager,
) : InstalledAppPackageReader {

    /** 在 IO 线程先过滤扫描范围，再读取名称和 Application 图标；单项图标失败仅回退默认图标。 */
    override suspend fun loadInstalledApplications(showSystemApps: Boolean, selfPackageName: String): List<InstalledAppPackageEntry> {
        /** 本轮读取协程；系统调用返回后及每项资源加载之前检查取消，减少旧范围的无效图标读取。 */
        val scanContext = currentCoroutineContext()
        /** 当前用户可见安装应用；不使用 MATCH_DISABLED_COMPONENTS 强行展示禁用或冻结 App。 */
        val applications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }
        /** 本轮图标不可用数量；每次扫描只汇总一次日志，不记录应用身份。 */
        var iconFailureCount = 0
        /** 当前范围内的应用条目；被排除的应用不读取名称和图标。 */
        val entries = applications.mapNotNull { applicationInfo ->
            // 单次系统调用不能强制中断；返回后停止已取消任务，不继续读取后续应用资源。
            scanContext.ensureActive()
            /** 系统应用分类在读取资源之前确定，默认不加载系统应用的名称和图标。 */
            val isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
            if (!shouldReadInstalledApp(applicationInfo.packageName, selfPackageName, isSystemApp, showSystemApps)) {
                return@mapNotNull null
            }
            /** 当前应用标签；标签缺失时后续保留空名，不补充旧来源名称。 */
            val label = applicationInfo.loadLabel(packageManager).toString()
            /** Application 图标读取失败仅影响本行；不再查询 Launcher Activity。 */
            val icon = try {
                applicationInfo.loadIcon(packageManager)
            } catch (error: CancellationException) {
                // 协程取消仍应终止扫描，不记为图标读取失败。
                throw error
            } catch (error: Exception) {
                null
            }
            if (icon == null) {
                iconFailureCount++
            }
            InstalledAppPackageEntry(
                packageName = applicationInfo.packageName,
                label = label,
                isSystemApp = isSystemApp,
                icon = icon,
            )
        }
        if (iconFailureCount > 0) {
            logW("InstalledAppReader") {
                "安装应用图标读取失败 reasonCode=installed_app_icon_unavailable failureCount=$iconFailureCount " +
                    "showSystemApps=$showSystemApps"
            }
        }
        return entries
    }
}
