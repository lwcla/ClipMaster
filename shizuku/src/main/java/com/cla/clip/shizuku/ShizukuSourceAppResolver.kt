package com.cla.clip.shizuku

import android.graphics.Bitmap
import com.cla.clip.base.general.utils.iconBitmap
import com.cla.clip.base.general.utils.logW
import com.cla.clip.base.general.utils.toStableHash

/**
 * Shizuku 侧来源应用展示信息解析器。
 *
 * 来源包可能在 AppOps 回调后被卸载或被系统隐藏；解析失败时必须回退 Unknown/空图标，不能阻断剪贴 payload 提交。
 *
 * @param packageManager Shizuku 进程可用的 PackageManager。
 */
internal class ShizukuSourceAppResolver(
    private val sourceAppReader: ShizukuSourceAppReader,
) {

    /**
     * 使用 Android PackageManager 构造来源解析器。
     *
     * @param packageManager Shizuku 进程可用的 PackageManager。
     */
    constructor(packageManager: android.content.pm.PackageManager) : this(AndroidShizukuSourceAppReader(packageManager))

    /**
     * 解析来源应用展示信息。
     *
     * @param clipPackageName AppOps 回调给出的来源包名，可能为空。
     */
    fun resolve(clipPackageName: String?): ShizukuSourceAppInfo {
        if (clipPackageName.isNullOrBlank()) {
            return ShizukuSourceAppInfo(packageName = clipPackageName, appName = UNKNOWN_APP_NAME, bitmap = null, iconHash = null)
        }

        return runCatching {
            /** 来源应用读取结果；失败由外层 runCatching 回退 Unknown。 */
            val readResult = sourceAppReader.read(clipPackageName)
            /** 来源应用展示名；为空时使用 Unknown，保持旧链路展示语义。 */
            val appName = readResult.appName.takeUnless { it.isNullOrBlank() } ?: UNKNOWN_APP_NAME
            /** 来源应用图标 Bitmap；为空表示本次没有可同步图标。 */
            val bitmap = readResult.bitmap
            /** 来源图标稳定 hash；为空表示图标解析失败或不可用。 */
            val iconHash = bitmap?.toStableHash()
            ShizukuSourceAppInfo(packageName = clipPackageName, appName = appName, bitmap = bitmap, iconHash = iconHash)
        }.getOrElse { throwable ->
            logW(ClipboardShizukuService.TAG) {
                "来源应用解析失败 packageName=$clipPackageName reason=${throwable::class.java.simpleName.ifBlank { "Throwable" }}"
            }
            ShizukuSourceAppInfo(packageName = clipPackageName, appName = UNKNOWN_APP_NAME, bitmap = null, iconHash = null)
        }
    }

    companion object {
        /** 来源应用未知时的展示名，保持旧链路兜底语义。 */
        private const val UNKNOWN_APP_NAME = "Unknown"
    }
}

/**
 * 来源应用读取器。
 *
 * @param clipPackageName AppOps 回调给出的来源包名，已保证非空非空白。
 */
internal fun interface ShizukuSourceAppReader {
    fun read(clipPackageName: String): ShizukuSourceAppReadResult
}

/**
 * 来源应用读取结果。
 *
 * @param appName 来源应用展示名，可能为空，由 resolver 统一回退。
 * @param bitmap 来源应用图标，可能为空。
 */
internal data class ShizukuSourceAppReadResult(
    val appName: String?,
    val bitmap: Bitmap?,
)

/** Android PackageManager 来源应用读取实现。 */
private class AndroidShizukuSourceAppReader(
    /** Shizuku 进程可用的 PackageManager。 */
    private val packageManager: android.content.pm.PackageManager,
) : ShizukuSourceAppReader {
    override fun read(clipPackageName: String): ShizukuSourceAppReadResult {
        /** 来源应用包信息；读取失败说明来源包已不可见或卸载。 */
        val packageInfo = packageManager.getPackageInfo(clipPackageName, 0)
        /** 来源应用展示名；为空时由上层 resolver 回退 Unknown。 */
        val appName = packageInfo.applicationInfo?.loadLabel(packageManager)?.toString()
        /** 来源应用图标 Drawable；优先读取桌面入口图标，避免历史来源回退继续保存系统默认图标。 */
        val bitmap = ShizukuAppIconLoader
            .loadBestIcon(packageManager, clipPackageName)
            .iconBitmap()
        return ShizukuSourceAppReadResult(appName = appName, bitmap = bitmap)
    }
}

/**
 * Shizuku 侧来源应用展示信息。
 *
 * @param packageName AppOps 回调给出的来源包名。
 * @param appName 来源应用展示名；解析失败时为 Unknown。
 * @param bitmap 来源应用图标 Bitmap；解析失败或不可用时为空。
 * @param iconHash 来源图标稳定 hash；没有图标时为空。
 */
internal data class ShizukuSourceAppInfo(
    val packageName: String?,
    val appName: String,
    val bitmap: Bitmap?,
    val iconHash: String?,
)
