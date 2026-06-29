package com.cla.clip.master.installedapps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logW
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Coil 请求模型：按当前安装应用包名从主进程 PackageManager 读取图标。 */
data class InstalledAppIconRequest(
    /** 应用包名；只作为图标加载身份，不进入过滤匹配之外的持久化状态。 */
    val packageName: String,
)

/**
 * 当前安装应用图标读取器。
 *
 * 只在可见行触发，优先读取 Launcher Activity 图标，失败后回退 Application 图标。
 */
class InstalledAppIconLoader(
    /** 应用 Context；用于拿 PackageManager，避免持有 Activity。 */
    context: Context,
) {

    /** 系统 PackageManager；图标读取由 Coil 后台 fetcher 触发。 */
    private val packageManager: PackageManager = context.applicationContext.packageManager

    /** 当前 App 包名；理论上过滤页不会请求自身，这里仍拒绝以保持边界一致。 */
    private val selfPackageName: String = context.applicationContext.packageName

    /** 按包名读取当前安装应用图标；失败返回 null 交给 UI 通用图标兜底。 */
    fun loadIcon(packageName: String): Drawable? {
        /** 标准化包名；空包名或当前 App 自身不读取图标。 */
        val normalizedPackageName = packageName.trim().takeIf { value -> value.isNotEmpty() } ?: return null
        if (normalizedPackageName == selfPackageName) {
            logIconFailureOnce(normalizedPackageName, "self_package_rejected")
            return null
        }
        /** Launcher Activity 图标；用户最容易识别，优先于 Application 图标。 */
        val launcherIcon = loadLauncherActivityIcon(normalizedPackageName)
        if (launcherIcon != null) {
            return launcherIcon
        }
        /** Application 图标；部分应用没有 Launcher 入口时仍可作为展示兜底。 */
        val applicationIcon = loadApplicationIcon(normalizedPackageName)
        if (applicationIcon == null) {
            logIconFailureOnce(normalizedPackageName, "installed_app_icon_unavailable")
        }
        return applicationIcon
    }

    /** 读取指定包名的 Launcher Activity 图标；没有入口或读取失败时返回 null。 */
    private fun loadLauncherActivityIcon(packageName: String): Drawable? {
        return try {
            /** Launcher 查询 Intent；限定 package 后只查当前应用可启动入口。 */
            val launcherIntent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName)
            /** 当前包名的 Launcher Activity 列表；不请求禁用组件。 */
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(launcherIntent, 0)
            }
            /** 第一个可用 Activity 图标；PackageManager 会处理默认图标兜底。 */
            resolveInfos.firstOrNull()?.loadIcon(packageManager)
        } catch (error: Throwable) {
            logIconFailureOnce(packageName, "launcher_icon_failed", error)
            null
        }
    }

    /** 读取指定包名的 Application 图标；包名不可见或不存在时返回 null。 */
    private fun loadApplicationIcon(packageName: String): Drawable? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationIcon(
                    packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0)),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationIcon(packageName)
            }
        } catch (error: Throwable) {
            logIconFailureOnce(packageName, "application_icon_failed", error)
            null
        }
    }
}

/** 当前安装应用图标 Coil fetcher；把 PackageManager Drawable 转成 Coil Image。 */
private class InstalledAppIconFetcher(
    /** 图标请求模型；只包含包名。 */
    private val request: InstalledAppIconRequest,
    /** 当前安装应用图标读取器。 */
    private val iconLoader: InstalledAppIconLoader,
) : Fetcher {

    override suspend fun fetch(): ImageFetchResult? {
        /** 从 PackageManager 读取到的图标 Drawable；为空时委托 Coil 展示 error painter。 */
        val drawable = iconLoader.loadIcon(request.packageName) ?: return null
        return ImageFetchResult(
            image = drawable.asImage(),
            isSampled = false,
            dataSource = DataSource.MEMORY,
        )
    }

    /** Fetcher 工厂；注册到过滤页专用 ImageLoader。 */
    class Factory(
        /** 当前安装应用图标读取器；由页面专用 ImageLoader 持有。 */
        private val iconLoader: InstalledAppIconLoader,
    ) : Fetcher.Factory<InstalledAppIconRequest> {

        override fun create(
            data: InstalledAppIconRequest,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher {
            return InstalledAppIconFetcher(request = data, iconLoader = iconLoader)
        }
    }
}

/** 当前安装应用图标缓存 key；按包名缓存可见行图标。 */
private class InstalledAppIconKeyer : Keyer<InstalledAppIconRequest> {

    override fun key(data: InstalledAppIconRequest, options: Options): String {
        return "installed-app-icon:${data.packageName}"
    }
}

/** 构建过滤页当前安装应用图标专用 ImageLoader。 */
@Composable
fun rememberInstalledAppIconImageLoader(): ImageLoader {
    /** 当前 Context；只使用 applicationContext 构建 ImageLoader，避免泄露页面。 */
    val context = LocalContext.current.applicationContext
    return remember(context) {
        /** 当前安装应用图标读取器；只服务这个 ImageLoader 的 fetcher。 */
        val iconLoader = InstalledAppIconLoader(context)
        ImageLoader.Builder(context)
            .components {
                add(InstalledAppIconKeyer())
                add(InstalledAppIconFetcher.Factory(iconLoader))
            }
            .build()
    }
}

/** 已记录失败的包名 hash 集合；限制图标失败日志只记录首次出现，避免列表滚动刷屏。 */
private val loggedIconFailureKeys = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

/** 按包名 hash 首次记录图标读取失败；不输出完整包名、图标内容或应用列表。 */
private fun logIconFailureOnce(packageName: String, reasonCode: String, error: Throwable? = null) {
    /** 图标失败去重 key；同包名同原因只记录一次。 */
    val failureKey = "${packageNameHash(packageName)}:$reasonCode"
    if (!loggedIconFailureKeys.add(failureKey)) {
        return
    }
    if (error == null) {
        logD("InstalledAppIconLoader") {
            "installed_app_icon_failed packageHash=${packageNameHash(packageName)} reasonCode=$reasonCode"
        }
    } else {
        logW("InstalledAppIconLoader") {
            "installed_app_icon_failed packageHash=${packageNameHash(packageName)} reasonCode=$reasonCode " +
                "errorType=${error::class.java.simpleName}"
        }
    }
}

/** 生成低敏包名 hash；只用于日志定位同一包名，不可逆输出完整身份。 */
private fun packageNameHash(packageName: String): String {
    /** SHA-256 摘要；截断后足够定位本轮日志，不暴露完整包名。 */
    val digest = MessageDigest.getInstance("SHA-256").digest(packageName.toByteArray(Charsets.UTF_8))
    return digest.take(6).joinToString(separator = "") { byte -> "%02x".format(byte) }
}
