package com.cla.clip.base.general.backup

import com.cla.clip.base.general.config.ClipSourceBlockRules
import com.cla.clip.base.general.config.clip.ClipSettingsSnapshot
import com.cla.clip.base.general.dao.ClipData
import com.cla.clip.base.general.dao.LinkPreviewData
import com.cla.clip.base.general.dao.SearchHistoryData
import com.cla.clip.base.general.dao.SourceAppData

/** 导出剪贴记录时只保留用户可见状态和恢复所需字段。 */
internal fun ClipData.toBackupClip(): BackupClip {
    return BackupClip(
        id = id,
        content = content,
        timestamp = timestamp,
        pinnedTime = pinnedTime,
        isFolded = isFolded,
        foldedAt = foldedAt,
        deletedAt = deletedAt,
        link = link,
        sourceAppPackage = sourceAppPackage
    )
}

/**
 * 恢复剪贴记录时重新计算 searchText，不依赖旧备份中的派生字段。
 *
 * 搜索页依赖内容、来源 App 名称/包名和链接预览标题等组合字段；恢复时从同一备份包里的来源与预览缓存重建，
 * 避免旧备份缺少派生字段或派生字段过期导致恢复后搜索命中能力下降。
 */
internal fun BackupClip.toEntity(
    sourceApp: BackupSourceApp?,
    linkPreview: BackupLinkPreview?,
    timeNormalizer: BackupRestoreTimeNormalizer,
): ClipData {
    val rebuiltSearchText = buildString {
        append(content)
        append(link.orEmpty())
        append(sourceApp?.appName.orEmpty())
        append(sourceAppPackage.orEmpty())
        append(linkPreview?.title.orEmpty())
        append(linkPreview?.description.orEmpty())
        append(linkPreview?.siteName.orEmpty())
    }
    return ClipData(
        id = id,
        content = content,
        timestamp = timeNormalizer.normalizeUserTime(timestamp),
        pinnedTime = timeNormalizer.normalizeUserTime(pinnedTime),
        isFolded = isFolded,
        foldedAt = timeNormalizer.normalizeUserTime(foldedAt),
        deletedAt = timeNormalizer.normalizeUserTime(deletedAt),
        link = link,
        sourceAppPackage = sourceAppPackage,
        searchText = rebuiltSearchText
    )
}

/** 估算剪贴记录最后用户状态时间，用于旧备份不覆盖本地新状态。 */
internal fun ClipData.lastUserStateTime(): Long {
    return maxOf(timestamp, pinnedTime, foldedAt, deletedAt)
}

/** 来源 App 实体转备份字段。 */
internal fun SourceAppData.toBackupSourceApp(): BackupSourceApp {
    return BackupSourceApp(
        packageName = packageName,
        appName = appName,
        iconPath = iconPath,
        primaryColor = primaryColor,
        iconHash = iconHash
    )
}

/** 来源 App 备份字段转实体。 */
internal fun BackupSourceApp.toEntity(): SourceAppData {
    return SourceAppData(
        packageName = packageName,
        appName = appName,
        iconPath = iconPath,
        primaryColor = primaryColor,
        iconHash = iconHash
    )
}

/** 来源 App 备份字段是否与本地已有缓存一致，用于重复恢复时报告跳过而不是新增。 */
internal fun SourceAppData.sameBackupContent(other: SourceAppData): Boolean {
    return appName == other.appName &&
        iconPath == other.iconPath &&
        primaryColor == other.primaryColor &&
        iconHash == other.iconHash
}

/** 链接预览实体转备份字段。 */
internal fun LinkPreviewData.toBackupLinkPreview(): BackupLinkPreview {
    return BackupLinkPreview(
        link = link,
        title = title,
        description = description,
        imageUrl = imageUrl,
        siteName = siteName
    )
}

/** 链接预览备份字段转实体。 */
internal fun BackupLinkPreview.toEntity(): LinkPreviewData {
    return LinkPreviewData(
        link = link,
        title = title,
        description = description,
        imageUrl = imageUrl,
        siteName = siteName
    )
}

/** 链接预览备份字段是否与本地已有缓存一致，用于重复恢复时报告跳过而不是新增。 */
internal fun LinkPreviewData.sameBackupContent(other: LinkPreviewData): Boolean {
    return title == other.title &&
        description == other.description &&
        imageUrl == other.imageUrl &&
        siteName == other.siteName
}

/** 搜索历史实体转备份字段。 */
internal fun SearchHistoryData.toBackupSearchHistory(): BackupSearchHistory {
    return BackupSearchHistory(
        id = id,
        query = query,
        normalizedQuery = normalizedQuery,
        isFolded = isFolded,
        updatedAt = updatedAt
    )
}

/** 搜索历史备份字段转实体。 */
internal fun BackupSearchHistory.toEntity(): SearchHistoryData {
    return SearchHistoryData(
        id = id,
        query = query,
        normalizedQuery = normalizedQuery,
        isFolded = isFolded,
        updatedAt = updatedAt
    )
}

/** 搜索历史备份字段是否与本地已有记录一致；自增 id 不参与业务幂等判断。 */
internal fun SearchHistoryData.sameBackupContent(other: SearchHistoryData): Boolean {
    return query == other.query &&
        normalizedQuery == other.normalizedQuery &&
        isFolded == other.isFolded &&
        updatedAt == other.updatedAt
}

/** 已提交的剪贴设置快照转备份白名单，不包含本机安装态或密码。 */
internal fun ClipSettingsSnapshot.toBackupSettings(): BackupSettings {
    return BackupSettings(
        clipItemQuickAction = quickAction.storageValue,
        recycleBinRetentionDays = recycleBinRetentionDays,
        blockedClipSourcePackages = ClipSourceBlockRules.normalizePackageSet(blockedPackages).toList()
    )
}
