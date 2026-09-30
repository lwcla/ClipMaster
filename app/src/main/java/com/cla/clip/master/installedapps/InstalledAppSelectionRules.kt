package com.cla.clip.master.installedapps

/**
 * 判断应用是否属于本轮扫描范围；系统读取和结果规范化复用同一规则。
 *
 * 在读取名称、图标之前调用，排除空包名、自身及关闭开关时的系统应用，不改变保存名单。
 */
internal fun shouldReadInstalledApp(
    packageName: String,
    selfPackageName: String,
    isSystemApp: Boolean,
    showSystemApps: Boolean,
): Boolean {
    /** 规范化身份只用于扫描排除，不触及持久过滤规则。 */
    val normalizedPackageName = packageName.trim()
    return normalizedPackageName.isNotEmpty() &&
        normalizedPackageName != selfPackageName.trim() &&
        (showSystemApps || !isSystemApp)
}
