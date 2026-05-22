package com.cla.clip.base.general.backup

import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.base.general.dao.ClipData
import com.cla.clip.base.general.dao.DownloadTaskData
import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.base.general.dao.ImageExtractItemData
import com.cla.clip.base.general.dao.LinkPreviewData
import com.cla.clip.base.general.dao.MagnetDownloadRecordData
import com.cla.clip.base.general.dao.MagnetSearchHistoryData
import com.cla.clip.base.general.dao.SearchHistoryData
import com.cla.clip.base.general.dao.SourceAppData
import com.cla.clip.base.general.magnet.MagnetInfoHashNormalizer
import com.cla.clip.base.general.magnet.MagnetSourceProvider
import com.cla.clip.base.general.magnet.MagnetTextNormalizer
import com.cla.clip.base.general.magnet.MagnetUriBuilder

/** 根据数据区生成数量摘要。 */
internal fun BackupData.toSummary(): BackupSummary {
    return BackupSummary(
        clipCount = clips.size,
        sourceAppCount = sourceApps.size,
        linkPreviewCount = linkPreviews.size,
        searchHistoryCount = searchHistories.size,
        magnetSearchHistoryCount = magnetSearchHistories.size,
        videoDownloadCount = videoDownloads.size,
        magnetDownloadRecordCount = magnetDownloadRecords.size,
        imageBatchCount = imageBatches.size,
        imageItemCount = imageItems.size
    )
}

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

/** 磁力搜索历史实体转备份字段。 */
internal fun MagnetSearchHistoryData.toBackupMagnetSearchHistory(): BackupMagnetSearchHistory {
    return BackupMagnetSearchHistory(
        id = id,
        query = query,
        normalizedQuery = normalizedQuery,
        updatedAt = updatedAt
    )
}

/** 磁力搜索历史备份字段转实体；超长文本按当前版本上限裁剪。 */
internal fun BackupMagnetSearchHistory.toEntity(): MagnetSearchHistoryData? {
    val displayQuery = MagnetTextNormalizer.normalizeDisplayQuery(query)
    val normalized = MagnetTextNormalizer.normalizeKey(normalizedQuery.ifBlank { displayQuery })
    if (displayQuery.isBlank() || normalized.isBlank()) return null
    return MagnetSearchHistoryData(
        id = id,
        query = displayQuery,
        normalizedQuery = normalized,
        updatedAt = updatedAt
    )
}

/** 磁力搜索历史备份字段是否与本地已有记录一致；自增 id 不参与业务幂等判断。 */
internal fun MagnetSearchHistoryData.sameBackupContent(other: MagnetSearchHistoryData): Boolean {
    return query == other.query &&
        normalizedQuery == other.normalizedQuery &&
        updatedAt == other.updatedAt
}

/** AppSetting 转备份设置白名单。 */
internal fun AppSetting.toBackupSettings(): BackupSettings {
    return BackupSettings(
        clipItemQuickAction = clipItemQuickAction.storageValue,
        recycleBinRetentionDays = recycleBinRetentionDays
    )
}

/** 视频任务实体转备份字段，过滤 Cookie、Referer、UA 和 pending 输出。 */
internal fun DownloadTaskData.toBackupVideoDownload(): BackupVideoDownload {
    return BackupVideoDownload(
        id = id,
        videoUrl = videoUrl,
        progress = progress,
        status = status,
        errorMsg = errorMsg,
        savePathHint = savePath,
        totalSize = totalSize,
        downloadedSize = downloadedSize,
        createTime = createTime,
        updateTime = updateTime,
        fileName = fileName
    )
}

/** 视频任务备份字段转实体，运行中/合并中任务恢复为失败，避免重装后恢复后台任务。 */
internal fun BackupVideoDownload.toEntity(): DownloadTaskData {
    val restoredStatus = when (status) {
        DownloadTaskData.STATUS_DOWNLOADING,
        DownloadTaskData.STATUS_MERGING -> DownloadTaskData.STATUS_FAILED
        else -> status
    }
    val restoredError = if (restoredStatus == DownloadTaskData.STATUS_FAILED && errorMsg.isNullOrBlank()) {
        "restore_interrupted"
    } else {
        errorMsg
    }
    return DownloadTaskData(
        id = id,
        videoUrl = videoUrl,
        referer = null,
        userAgent = null,
        cookie = null,
        progress = progress.coerceIn(0, 100),
        status = restoredStatus,
        errorMsg = restoredError,
        savePath = savePathHint,
        pendingOutputUri = null,
        totalSize = totalSize,
        downloadedSize = downloadedSize,
        createTime = createTime,
        updateTime = updateTime,
        fileName = fileName
    )
}

/** 磁力下载记录实体转备份字段。 */
internal fun MagnetDownloadRecordData.toBackupMagnetDownloadRecord(): BackupMagnetDownloadRecord {
    return BackupMagnetDownloadRecord(
        id = id,
        sourceId = sourceId,
        infoHash = infoHash,
        title = title,
        detailUrl = detailUrl,
        sizeBytes = sizeBytes,
        category = category,
        magnetUri = magnetUri,
        lastSourceQuery = lastSourceQuery,
        firstUsedAt = firstUsedAt,
        lastUsedAt = lastUsedAt
    )
}

/** 磁力下载记录备份字段转实体；未知来源、非法 infoHash 或无法重建 magnet 时跳过。 */
internal fun BackupMagnetDownloadRecord.toEntity(): MagnetDownloadRecordData? {
    if (!MagnetSourceProvider.isAllowed(sourceId)) return null
    val normalizedHash = MagnetInfoHashNormalizer.normalize(infoHash) ?: return null
    val safeTitle = title.trim().ifBlank { normalizedHash }
    val safeMagnet = magnetUri
        ?.takeIf { it.startsWith("magnet:", ignoreCase = true) }
        ?: MagnetUriBuilder.build(normalizedHash, safeTitle)
        ?: return null
    return MagnetDownloadRecordData(
        id = id,
        sourceId = sourceId,
        infoHash = normalizedHash,
        title = safeTitle,
        detailUrl = detailUrl?.trim()?.takeIf { it.isNotBlank() },
        sizeBytes = sizeBytes?.takeIf { it >= 0L },
        category = category?.trim()?.takeIf { it.isNotBlank() },
        magnetUri = safeMagnet,
        lastSourceQuery = lastSourceQuery
            ?.let(MagnetTextNormalizer::normalizeDisplayQuery)
            ?.takeIf { it.isNotBlank() },
        firstUsedAt = firstUsedAt,
        lastUsedAt = lastUsedAt
    )
}

/** 磁力下载记录备份字段是否与本地已有记录一致；自增 id 不参与业务幂等判断。 */
internal fun MagnetDownloadRecordData.sameBackupContent(other: MagnetDownloadRecordData): Boolean {
    return sourceId == other.sourceId &&
        infoHash == other.infoHash &&
        title == other.title &&
        detailUrl == other.detailUrl &&
        sizeBytes == other.sizeBytes &&
        category == other.category &&
        magnetUri == other.magnetUri &&
        lastSourceQuery == other.lastSourceQuery &&
        firstUsedAt == other.firstUsedAt &&
        lastUsedAt == other.lastUsedAt
}

/** 图片批次实体转备份字段。 */
internal fun ImageExtractBatchData.toBackupImageBatch(): BackupImageBatch {
    return BackupImageBatch(
        id = id,
        pageUrl = pageUrl,
        pageName = pageName,
        status = status,
        totalCount = totalCount,
        successCount = successCount,
        failedCount = failedCount,
        filteredCount = filteredCount,
        outputDirHint = outputDir,
        errorMsg = errorMsg,
        createTime = createTime,
        updateTime = updateTime
    )
}

/** 图片批次备份字段转实体，下载中批次恢复为失败状态。 */
internal fun BackupImageBatch.toEntity(): ImageExtractBatchData {
    val restoredStatus = if (status == ImageExtractBatchData.STATUS_DOWNLOADING) {
        ImageExtractBatchData.STATUS_FAILED
    } else {
        status
    }
    return ImageExtractBatchData(
        id = id,
        pageUrl = pageUrl,
        pageName = pageName,
        status = restoredStatus,
        totalCount = totalCount,
        successCount = successCount,
        failedCount = failedCount,
        filteredCount = filteredCount,
        outputDir = outputDirHint,
        errorMsg = errorMsg ?: if (restoredStatus == ImageExtractBatchData.STATUS_FAILED) "restore_interrupted" else null,
        createTime = createTime,
        updateTime = updateTime
    )
}

/** 图片项实体转备份字段，过滤 Cookie 和临时路径。 */
internal fun ImageExtractItemData.toBackupImageItem(): BackupImageItem {
    return BackupImageItem(
        id = id,
        batchId = batchId,
        url = url,
        referer = referer,
        userAgent = userAgent,
        displayOrder = displayOrder,
        width = width,
        height = height,
        status = status,
        outputUriHint = outputUri,
        finalName = finalName,
        errorMsg = errorMsg
    )
}

/** 图片项备份字段转实体，临时路径和 Cookie 不恢复。 */
internal fun BackupImageItem.toEntity(): ImageExtractItemData {
    return ImageExtractItemData(
        id = id,
        batchId = batchId,
        url = url,
        referer = referer,
        userAgent = userAgent,
        cookie = null,
        displayOrder = displayOrder,
        width = width,
        height = height,
        status = status,
        tempPath = null,
        outputUri = outputUriHint,
        finalName = finalName,
        errorMsg = errorMsg
    )
}

/** 图片项备份字段是否与本地已有记录一致；Cookie 和临时路径不会从备份恢复，因此不参与比较。 */
internal fun ImageExtractItemData.sameBackupContent(other: ImageExtractItemData): Boolean {
    return batchId == other.batchId &&
        url == other.url &&
        referer == other.referer &&
        userAgent == other.userAgent &&
        displayOrder == other.displayOrder &&
        width == other.width &&
        height == other.height &&
        status == other.status &&
        outputUri == other.outputUri &&
        finalName == other.finalName &&
        errorMsg == other.errorMsg
}
