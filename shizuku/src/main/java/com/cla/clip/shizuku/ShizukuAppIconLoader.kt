package com.cla.clip.shizuku

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * Shizuku 进程内的应用图标读取器。
 *
 * 过滤页和历史来源图标都应优先展示用户在桌面看到的 Launcher 图标；没有桌面入口时再回退应用级图标。
 */
internal object ShizukuAppIconLoader {

    /**
     * 读取包名最适合展示的图标。
     *
     * @param packageManager Shizuku 进程可见的 PackageManager。
     * @param packageName 当前应用包名。
     */
    fun loadBestIcon(packageManager: PackageManager, packageName: String): Drawable? {
        /** 桌面入口图标；Flutter、换壳和部分厂商应用常把真实图标放在 Activity 上。 */
        val launcherIcon = loadLauncherActivityIcon(packageManager, packageName)
        if (launcherIcon != null) {
            return launcherIcon
        }
        /** 应用级图标兜底；系统组件或无桌面入口应用仍可能只有 Application 图标。 */
        val applicationInfo = packageManager.getPackageInfo(packageName, 0).applicationInfo
        return applicationInfo?.loadIcon(packageManager)
    }

    /**
     * 读取包名对应的 Launcher Activity 图标。
     *
     * @param packageManager Shizuku 进程可见的 PackageManager。
     * @param packageName 当前应用包名。
     */
    private fun loadLauncherActivityIcon(packageManager: PackageManager, packageName: String): Drawable? {
        /** Launcher 查询 Intent；限制 package 后只查当前应用自己的桌面入口。 */
        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        /** Launcher 入口列表；多入口时按 Activity 名称稳定选择，避免每轮 hash 抖动。 */
        val launcherActivities = packageManager.queryIntentActivities(launcherIntent, 0)
        /** 稳定的首个桌面入口；为空表示该包没有 Launcher Activity 或当前用户不可见。 */
        val launcherResolveInfo = launcherActivities
            .sortedBy { resolveInfo -> resolveInfo.activityInfo?.name.orEmpty() }
            .firstOrNull()
            ?: return null
        return launcherResolveInfo.loadIcon(packageManager)
    }
}
