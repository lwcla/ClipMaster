package com.cla.clip.base.general.repository

import com.cla.clip.base.general.dao.data.LastClipData

/** 来源应用名称为英文 Unknown 时的标准小写形式，表示包名或名称解析结果不可信。 */
private const val UNKNOWN_SOURCE_APP_NAME_EN = "unknown"

/** 来源应用名称为中文“未知”时的标准形式，表示 UI 兜底名称而不是真实应用名。 */
private const val UNKNOWN_SOURCE_APP_NAME_ZH = "未知"

/**
 * 判断来源 App 是否未知或不可信。
 *
 * @param packageName 来源应用包名；为空时无法稳定区分具体 App。
 * @param appName 来源应用名称；为空、Unknown 或“未知”时表示名称不可信。
 */
fun isUnknownClipSource(
    packageName: String?,
    appName: String?,
): Boolean {
    /** 规整后的来源包名；空字符串是历史未知来源筛选键，也应按未知处理。 */
    val normalizedPackageName = packageName?.trim().orEmpty()
    /** 规整后的来源应用名；仅用于判断名称是否缺失或落入未知兜底。 */
    val normalizedAppName = appName?.trim().orEmpty()
    /** 来源名的小写形式；用于大小写不敏感匹配英文 Unknown。 */
    val normalizedAppNameLowercase = normalizedAppName.lowercase()
    return normalizedPackageName.isEmpty() ||
        normalizedAppName.isEmpty() ||
        normalizedAppNameLowercase == UNKNOWN_SOURCE_APP_NAME_EN ||
        normalizedAppName == UNKNOWN_SOURCE_APP_NAME_ZH
}

/**
 * 判断最新记录是否应在进入 Repository 前按连续重复跳过。
 *
 * 这里只做轻量短路：同来源重复、本次未知遇到已有明确来源、或双方都未知时跳过；
 * 如果已有记录来源未知但本次来源明确，则不能提前跳过，必须进入 Repository 覆盖升级旧记录。
 *
 * @param currentContent 本次待保存的剪贴内容，已由调用方去除首尾空白。
 * @param currentSourcePackage 本次来源包名，可能为空。
 * @param currentSourceAppName 本次来源应用名，可能为空或 Unknown。
 * @param lastClip 最近一条未删除剪贴记录的轻量投影。
 */
fun shouldSkipConsecutiveDuplicateClip(
    currentContent: String,
    currentSourcePackage: String?,
    currentSourceAppName: String?,
    lastClip: LastClipData?,
): Boolean {
    if (lastClip == null || currentContent != lastClip.content) {
        return false
    }

    /** 本次来源包名；只在非空时参与同来源身份判断。 */
    val currentPackage = currentSourcePackage.normalizedClipSourcePackage()
    /** 最近记录来源包名；只在非空时参与同来源身份判断。 */
    val lastPackage = lastClip.sourceAppPackage.normalizedClipSourcePackage()
    /** 本次来源是否未知；未知来源不能用于升级已有明确来源。 */
    val currentSourceUnknown = isUnknownClipSource(currentSourcePackage, currentSourceAppName)
    /** 最近记录来源是否未知；未知记录遇到明确来源时应放行给 Repository 覆盖。 */
    val lastSourceUnknown = isUnknownClipSource(lastClip.sourceAppPackage, lastClip.sourceAppName)
    /** 两次来源是否拥有同一个非空包名；相同包名通常代表同一来源 App。 */
    val samePackage = currentPackage != null && currentPackage == lastPackage

    if (samePackage && currentSourceUnknown.not() && lastSourceUnknown) {
        return false
    }

    return samePackage || currentSourceUnknown
}

/** 将来源包名规整成可比较形式；空白包名返回 null，避免把未知来源当成真实 App。 */
internal fun String?.normalizedClipSourcePackage(): String? {
    return this?.trim()?.takeIf { packageName -> packageName.isNotEmpty() }
}
