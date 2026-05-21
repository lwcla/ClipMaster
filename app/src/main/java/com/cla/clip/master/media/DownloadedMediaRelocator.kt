package com.cla.clip.master.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.base.general.dao.DownloadTaskData
import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.base.general.dao.ImageExtractItemData
import com.cla.clip.base.general.dao.ImageMediaReferenceUpdate
import com.cla.clip.base.general.dao.VideoMediaReferenceUpdate
import com.cla.clip.base.general.repository.DownloadRepository
import com.cla.clip.base.general.repository.ImageExtractRepository
import com.cla.clip.base.general.utils.hasPermission
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import com.cla.clip.base.general.utils.normalizeImageOutputDir
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

private const val TAG = "DownloadedMediaRelocator"
private const val MEDIA_ROOT_DIR = "clipMaster"
private const val MEDIA_RELATIVE_PATH = "DCIM/$MEDIA_ROOT_DIR"
private const val LEGACY_IMAGE_PARENT_DIR = MEDIA_ROOT_DIR
private const val PAGE_SIZE = 100
private const val WRITE_CHUNK_SIZE = 100
private const val PROGRESS_LOG_INTERVAL_COUNT = 100
private const val PROGRESS_LOG_INTERVAL_MS = 10_000L
private const val VIDEO_EXTENSION = ".mp4"
private val permissionRecoverableImageReasons = setOf(
    MediaRelocationReason.FOLDER_MISSING,
    MediaRelocationReason.NO_CANDIDATE,
)

/**
 * 恢复后下载媒体重新定位协作者。
 *
 * 只负责验证现有媒体引用、按高可信规则扫描候选并写回新引用；不读取备份包、不改变下载状态，也不保存任务续跑状态。
 */
@Singleton
class DownloadedMediaRelocator @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val downloadRepository: DownloadRepository,
    private val imageExtractRepository: ImageExtractRepository,
) {
    /** 只做数据库统计，不访问 MediaStore，用于扫描前给用户预估范围和耗时。 */
    suspend fun estimate(): MediaRelocationEstimate = withContext(Dispatchers.IO) {
        val videoCount = downloadRepository.countVideosForMediaRelocation()
        val (imageBatchCount, imageItemCount) = imageExtractRepository.countImagesForMediaRelocation()
        val estimate = MediaRelocationEstimate(
            videoCount = videoCount,
            imageBatchCount = imageBatchCount,
            imageItemCount = imageItemCount
        )
        logD(TAG) {
            "媒体重新定位预估 videoCount=$videoCount imageBatchCount=$imageBatchCount " +
                "imageItemCount=$imageItemCount estimateLevel=${estimate.durationLevel.logCode}"
        }
        estimate
    }

    /**
     * 验证旧引用是否仍可读，并判断是否需要申请图片或视频读取权限。
     *
     * Android 10+ 会先尝试无权限查询当前应用可见的 MediaStore 候选；如果候选已经足够定位，就不请求媒体读取权限。
     * 这里不会写数据库；如果旧引用都可读，调用方可以直接展示结果，避免无意义权限请求。
     */
    suspend fun prepare(estimate: MediaRelocationEstimate): MediaRelocationPreparation = withContext(Dispatchers.IO) {
        val report = MutableMediaRelocationReport()
        val missingImagePermissions = missingMediaPermissions(needsImages = true, needsVideos = false)
        val missingVideoPermissions = missingMediaPermissions(needsImages = false, needsVideos = true)
        var needsVideoScan = false
        var needsImageScan = false
        var needsVideoPermission = false
        var needsImagePermission = false
        val diagnostics = MediaRelocationPreparationDiagnostics()

        forEachVideo { task ->
            coroutineContext.ensureActive()
            if (isExistingVideoReadable(task)) {
                report.video.existingReadable++
            } else {
                needsVideoScan = true
                diagnostics.unreadableVideoCount++
                if (missingVideoPermissions.isNotEmpty()) {
                    val probe = probeVideoPermissionRequirement(task)
                    diagnostics.visibleVideoCandidateCount += probe.visibleUniqueCandidateCount
                    diagnostics.videoNoSearchKeyCount += probe.noSearchKeyCount
                    diagnostics.videoNotRecoverableCount += probe.notRecoverableCount
                    diagnostics.videoPermissionRequiredCount += probe.permissionRequiredCount
                    if (probe.needsPermission) {
                        needsVideoPermission = true
                    }
                }
            }
        }
        forEachImageBatch { batch, items ->
            coroutineContext.ensureActive()
            val unreadableItems = mutableListOf<ImageExtractItemData>()
            items.forEach { item ->
                if (isExistingImageReadable(batch, item)) {
                    report.image.existingReadable++
                } else {
                    needsImageScan = true
                    unreadableItems += item
                }
            }
            if (unreadableItems.isNotEmpty()) {
                diagnostics.unreadableImageBatchCount++
                diagnostics.unreadableImageItemCount += unreadableItems.size
                if (missingImagePermissions.isNotEmpty()) {
                    val probe = probeImagePermissionRequirement(batch, unreadableItems)
                    diagnostics.visibleImageCandidateCount += probe.visibleUniqueItemCount
                    diagnostics.imageNoSearchKeyItemCount += probe.noSearchKeyItemCount
                    diagnostics.imageNotRecoverableItemCount += probe.notRecoverableItemCount
                    diagnostics.imagePermissionRequiredItemCount += probe.permissionRequiredItemCount
                    if (probe.needsPermission) {
                        needsImagePermission = true
                        diagnostics.imagePermissionRequiredBatchCount++
                    }
                }
            }
        }

        val requiredPermissions = buildList {
            if (needsImagePermission) addAll(missingImagePermissions)
            if (needsVideoPermission) addAll(missingVideoPermissions)
        }.distinct()
        val preparation = MediaRelocationPreparation(
            estimate = estimate,
            needsImageScan = needsImageScan,
            needsVideoScan = needsVideoScan,
            needsImagePermission = needsImagePermission,
            needsVideoPermission = needsVideoPermission,
            requiredPermissions = requiredPermissions,
            existingReadableVideoCount = report.video.existingReadable,
            existingReadableImageCount = report.image.existingReadable,
            permissionRequiredVideoCount = diagnostics.videoPermissionRequiredCount,
            permissionRequiredImageItemCount = diagnostics.imagePermissionRequiredItemCount,
        )
        logD(TAG) {
            "媒体重新定位准备完成 needsVideoScan=$needsVideoScan needsImageScan=$needsImageScan " +
                "needsVideoPermission=$needsVideoPermission needsImagePermission=$needsImagePermission " +
                "permissionCount=${requiredPermissions.size} existingVideo=${report.video.existingReadable} " +
                "existingImage=${report.image.existingReadable}"
        }
        logD(TAG) {
            "媒体重新定位准备诊断 apiLevel=${Build.VERSION.SDK_INT} " +
                "missingVideoPermission=${missingVideoPermissions.isNotEmpty()} " +
                "missingImagePermission=${missingImagePermissions.isNotEmpty()} " +
                "unreadableVideo=${diagnostics.unreadableVideoCount} " +
                "unreadableImageBatches=${diagnostics.unreadableImageBatchCount} " +
                "unreadableImageItems=${diagnostics.unreadableImageItemCount} " +
                "visibleVideoCandidates=${diagnostics.visibleVideoCandidateCount} " +
                "visibleImageItems=${diagnostics.visibleImageCandidateCount} " +
                "permissionRequiredVideo=${diagnostics.videoPermissionRequiredCount} " +
                "permissionRequiredImageBatches=${diagnostics.imagePermissionRequiredBatchCount} " +
                "permissionRequiredImageItems=${diagnostics.imagePermissionRequiredItemCount} " +
                "noSearchKeyVideo=${diagnostics.videoNoSearchKeyCount} " +
                "noSearchKeyImageItems=${diagnostics.imageNoSearchKeyItemCount} " +
                "notRecoverableVideo=${diagnostics.videoNotRecoverableCount} " +
                "notRecoverableImageItems=${diagnostics.imageNotRecoverableItemCount}"
        }
        preparation
    }

    /** 正式扫描和写回；调用方应在权限齐备且用户确认后再进入该方法。 */
    suspend fun relocate(
        estimate: MediaRelocationEstimate,
        onProgress: (MediaRelocationProgress) -> Unit,
    ): MediaRelocationReport = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val report = MutableMediaRelocationReport()
        val progress = MutableMediaRelocationProgress(
            totalVideos = estimate.videoCount,
            totalImageBatches = estimate.imageBatchCount,
            totalImageItems = estimate.imageItemCount,
        )
        val videoUpdates = mutableListOf<VideoMediaReferenceUpdate>()
        val imageUpdates = mutableListOf<ImageMediaReferenceUpdate>()
        val progressLogger = MediaRelocationProgressLogger()

        fun publish(stage: MediaRelocationStage) {
            val snapshot = progress.toProgress(stage, report.toReport())
            onProgress(snapshot)
            progressLogger.maybeLog(snapshot)
        }

        logI(TAG) {
            "媒体重新定位开始 videoCount=${estimate.videoCount} imageBatchCount=${estimate.imageBatchCount} " +
                "imageItemCount=${estimate.imageItemCount} estimateLevel=${estimate.durationLevel.logCode}"
        }
        publish(MediaRelocationStage.VerifyingExisting)

        forEachVideo { task ->
            coroutineContext.ensureActive()
            progress.processedVideos++
            if (isExistingVideoReadable(task)) {
                report.video.existingReadable++
                publish(MediaRelocationStage.VerifyingExisting)
                return@forEachVideo
            }
            when (val result = relocateVideo(task)) {
                is VideoRelocationResult.Relocated -> {
                    videoUpdates += VideoMediaReferenceUpdate(taskId = task.id, savePath = result.savePath)
                    if (videoUpdates.size >= WRITE_CHUNK_SIZE) {
                        progress.relocatedCount += flushVideoUpdates(videoUpdates, report)
                    }
                }
                is VideoRelocationResult.Skipped -> report.video.add(result.reasonCode)
            }
            publish(MediaRelocationStage.ScanningVideos)
        }
        progress.relocatedCount += flushVideoUpdates(videoUpdates, report)

        forEachImageBatch { batch, items ->
            coroutineContext.ensureActive()
            progress.processedImageBatches++
            val unreadableItems = mutableListOf<ImageExtractItemData>()
            items.forEach { item ->
                progress.processedImageItems++
                if (isExistingImageReadable(batch, item)) {
                    report.image.existingReadable++
                } else {
                    unreadableItems += item
                }
            }
            if (unreadableItems.isEmpty()) {
                publish(MediaRelocationStage.VerifyingExisting)
                return@forEachImageBatch
            }

            val batchResult = relocateImageBatch(batch, unreadableItems)
            report.imageFolderMissingBatches += batchResult.folderMissingBatches
            batchResult.itemResults.forEach { result ->
                when (result) {
                    is ImageItemRelocationResult.Relocated -> {
                        imageUpdates += ImageMediaReferenceUpdate(itemId = result.itemId, outputUri = result.outputUri)
                        if (imageUpdates.size >= WRITE_CHUNK_SIZE) {
                            progress.relocatedCount += flushImageUpdates(imageUpdates, report)
                        }
                    }
                    is ImageItemRelocationResult.Skipped -> report.image.add(result.reasonCode)
                }
            }
            publish(MediaRelocationStage.ScanningImages)
        }
        progress.relocatedCount += flushImageUpdates(imageUpdates, report)

        val finalReport = report.toReport()
        if (finalReport.totalRelocated > 0) {
            AppSetting.markBackupDirty()
        }
        logI(TAG) {
            "媒体重新定位完成 relocated=${finalReport.totalRelocated} existing=${finalReport.totalExistingReadable} " +
                "videoWriteFailed=${finalReport.video.writeFailed} imageWriteFailed=${finalReport.image.writeFailed} " +
                "durationMs=${System.currentTimeMillis() - startedAt}"
        }
        onProgress(progress.toProgress(MediaRelocationStage.Completed, finalReport))
        finalReport
    }

    private suspend fun flushVideoUpdates(
        updates: MutableList<VideoMediaReferenceUpdate>,
        report: MutableMediaRelocationReport,
    ): Int {
        if (updates.isEmpty()) return 0
        val chunk = updates.toList()
        updates.clear()
        return runCatching {
            downloadRepository.updateVideoMediaReferencesForRelocation(chunk)
        }.fold(
            onSuccess = {
                report.video.relocated += chunk.size
                chunk.size
            },
            onFailure = { tr ->
                report.video.writeFailed += chunk.size
                logE(TAG, tr) { "媒体重新定位视频写回失败 count=${chunk.size} reasonCode=${MediaRelocationReason.WRITE_FAILED}" }
                0
            }
        )
    }

    private suspend fun flushImageUpdates(
        updates: MutableList<ImageMediaReferenceUpdate>,
        report: MutableMediaRelocationReport,
    ): Int {
        if (updates.isEmpty()) return 0
        val chunk = updates.toList()
        updates.clear()
        return runCatching {
            imageExtractRepository.updateImageMediaReferencesForRelocation(chunk)
        }.fold(
            onSuccess = {
                report.image.relocated += chunk.size
                chunk.size
            },
            onFailure = { tr ->
                report.image.writeFailed += chunk.size
                logE(TAG, tr) { "媒体重新定位图片写回失败 count=${chunk.size} reasonCode=${MediaRelocationReason.WRITE_FAILED}" }
                0
            }
        )
    }

    private suspend fun forEachVideo(block: suspend (DownloadTaskData) -> Unit) {
        var lastId = 0L
        while (true) {
            val page = downloadRepository.loadVideosForMediaRelocation(lastId, PAGE_SIZE)
            if (page.isEmpty()) return
            page.forEach { task ->
                block(task)
                lastId = task.id
            }
        }
    }

    private suspend fun forEachImageBatch(block: suspend (ImageExtractBatchData, List<ImageExtractItemData>) -> Unit) {
        var lastBatchId = 0L
        while (true) {
            val page = imageExtractRepository.loadImageBatchSummariesForMediaRelocation(lastBatchId, PAGE_SIZE)
            if (page.isEmpty()) return
            page.forEach { summary ->
                val pair = imageExtractRepository.getBatchWithSuccessfulItemsForMediaRelocation(summary.batchId)
                if (pair != null) {
                    block(pair.first, pair.second)
                }
                lastBatchId = summary.batchId
            }
        }
    }

    private fun isExistingVideoReadable(task: DownloadTaskData): Boolean {
        return task.savePath.toMediaRef()?.isReadable() == true
    }

    private fun isExistingImageReadable(batch: ImageExtractBatchData, item: ImageExtractItemData): Boolean {
        item.outputUri.toMediaRef()?.let { return it.isReadable() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false
        val finalName = item.finalName?.takeIf { it.isNotBlank() } ?: return false
        val folder = resolveLegacyImageFolder(batch.outputDir) ?: return false
        return File(folder, finalName).isFile
    }

    private fun relocateVideo(task: DownloadTaskData): VideoRelocationResult {
        val candidates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryMediaStoreVideoCandidates(task)
        } else {
            queryLegacyVideoCandidates(task)
        }
        return when (candidates.size) {
            0 -> VideoRelocationResult.Skipped(MediaRelocationReason.NO_CANDIDATE)
            1 -> VideoRelocationResult.Relocated(candidates.first().reference)
            else -> VideoRelocationResult.Skipped(MediaRelocationReason.MULTIPLE_CANDIDATES)
        }
    }

    private fun queryMediaStoreVideoCandidates(task: DownloadTaskData): List<MediaCandidate> {
        val acceptedNames = videoDisplayNameCandidates(task.fileName)
        val selectionParts = mutableListOf("${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            selectionParts += "${MediaStore.MediaColumns.IS_TRASHED} != 1"
        }
        val projection = buildList {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.MediaColumns.IS_TRASHED)
            }
        }.toTypedArray()
        val result = mutableListOf<MediaCandidate>()
        runCatching {
            appContext.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selectionParts.joinToString(" AND "),
                arrayOf(MEDIA_RELATIVE_PATH, "$MEDIA_RELATIVE_PATH/"),
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                while (idIndex >= 0 && nameIndex >= 0 && cursor.moveToNext()) {
                    val displayName = cursor.getString(nameIndex) ?: continue
                    if (displayName !in acceptedNames) continue
                    val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else null
                    if (!task.matchesSize(size)) continue
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idIndex))
                    result += MediaCandidate(reference = uri.toString())
                }
            }
        }.onFailure { tr ->
            logW(TAG, tr) { "媒体重新定位查询视频候选失败 reasonCode=${MediaRelocationReason.NO_CANDIDATE}" }
        }
        return result
    }

    private fun queryLegacyVideoCandidates(task: DownloadTaskData): List<MediaCandidate> {
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), MEDIA_ROOT_DIR)
        if (!folder.isDirectory) return emptyList()
        val acceptedNames = videoDisplayNameCandidates(task.fileName)
        return folder.listFiles()
            ?.filter { it.isFile && it.name in acceptedNames && task.matchesSize(it.length()) }
            ?.map { MediaCandidate(reference = it.absolutePath) }
            .orEmpty()
    }

    private fun relocateImageBatch(
        batch: ImageExtractBatchData,
        unreadableItems: List<ImageExtractItemData>,
    ): ImageBatchRelocationResult {
        val folder = normalizeImageOutputDir(batch.outputDir)
        if (folder == null) {
            return ImageBatchRelocationResult(
                folderMissingBatches = 1,
                itemResults = unreadableItems.map { ImageItemRelocationResult.Skipped(MediaRelocationReason.FOLDER_MISSING) }
            )
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            relocateMediaStoreImageBatch(folder, unreadableItems)
        } else {
            relocateLegacyImageBatch(batch.outputDir, unreadableItems)
        }
    }

    private fun relocateMediaStoreImageBatch(
        relativePath: String,
        unreadableItems: List<ImageExtractItemData>,
    ): ImageBatchRelocationResult {
        val candidatesByName = queryMediaStoreImageFolder(relativePath)
        if (candidatesByName.isEmpty()) {
            return ImageBatchRelocationResult(
                folderMissingBatches = 1,
                itemResults = unreadableItems.map { ImageItemRelocationResult.Skipped(MediaRelocationReason.FOLDER_MISSING) }
            )
        }
        val results = unreadableItems.map { item ->
            val finalName = item.finalName?.takeIf { it.isNotBlank() }
                ?: return@map ImageItemRelocationResult.Skipped(MediaRelocationReason.NO_CANDIDATE)
            val candidates = candidatesByName[finalName].orEmpty()
            when {
                candidates.isEmpty() -> ImageItemRelocationResult.Skipped(MediaRelocationReason.NO_CANDIDATE)
                candidates.size > 1 -> ImageItemRelocationResult.Skipped(MediaRelocationReason.MULTIPLE_CANDIDATES)
                !item.matchesDimensions(candidates.first()) -> ImageItemRelocationResult.Skipped(MediaRelocationReason.METADATA_MISMATCH)
                else -> ImageItemRelocationResult.Relocated(item.id, candidates.first().reference)
            }
        }
        return ImageBatchRelocationResult(folderMissingBatches = 0, itemResults = results)
    }

    private fun queryMediaStoreImageFolder(relativePath: String): Map<String, List<MediaCandidate>> {
        val selectionParts = mutableListOf("${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            selectionParts += "${MediaStore.MediaColumns.IS_TRASHED} != 1"
        }
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.MediaColumns.IS_TRASHED)
            }
        }.toTypedArray()
        val result = mutableMapOf<String, MutableList<MediaCandidate>>()
        runCatching {
            appContext.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selectionParts.joinToString(" AND "),
                arrayOf(relativePath, "$relativePath/"),
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val widthIndex = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                val heightIndex = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                while (idIndex >= 0 && nameIndex >= 0 && cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex) ?: continue
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idIndex))
                    val width = if (widthIndex >= 0) cursor.getInt(widthIndex).takeIf { it > 0 } else null
                    val height = if (heightIndex >= 0) cursor.getInt(heightIndex).takeIf { it > 0 } else null
                    result.getOrPut(name) { mutableListOf() } += MediaCandidate(
                        reference = uri.toString(),
                        width = width,
                        height = height
                    )
                }
            }
        }.onFailure { tr ->
            logW(TAG, tr) { "媒体重新定位查询图片文件夹失败 reasonCode=${MediaRelocationReason.FOLDER_MISSING}" }
        }
        return result
    }

    private fun relocateLegacyImageBatch(
        outputDir: String?,
        unreadableItems: List<ImageExtractItemData>,
    ): ImageBatchRelocationResult {
        val folder = resolveLegacyImageFolder(outputDir)
        if (folder == null) {
            return ImageBatchRelocationResult(
                folderMissingBatches = 1,
                itemResults = unreadableItems.map { ImageItemRelocationResult.Skipped(MediaRelocationReason.FOLDER_MISSING) }
            )
        }
        val names = folder.listFiles()
            ?.filter { it.isFile }
            ?.map { it.name }
            ?.toSet()
            .orEmpty()
        if (names.isEmpty()) {
            return ImageBatchRelocationResult(
                folderMissingBatches = 1,
                itemResults = unreadableItems.map { ImageItemRelocationResult.Skipped(MediaRelocationReason.FOLDER_MISSING) }
            )
        }
        val results = unreadableItems.map { item ->
            val finalName = item.finalName?.takeIf { it.isNotBlank() }
                ?: return@map ImageItemRelocationResult.Skipped(MediaRelocationReason.NO_CANDIDATE)
            if (finalName in names) {
                // 旧系统最终读取依赖 outputDir + finalName；进入扫描阶段还命中说明验证时可能遇到瞬时 IO 失败。
                ImageItemRelocationResult.Skipped(MediaRelocationReason.NO_CANDIDATE)
            } else {
                ImageItemRelocationResult.Skipped(MediaRelocationReason.NO_CANDIDATE)
            }
        }
        return ImageBatchRelocationResult(folderMissingBatches = 0, itemResults = results)
    }

    private fun resolveLegacyImageFolder(outputDir: String?): File? {
        val folderName = normalizeImageOutputDir(outputDir)
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val picturesFolder = File(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), LEGACY_IMAGE_PARENT_DIR),
            folderName
        )
        if (picturesFolder.isDirectory) return picturesFolder
        val dcimFolder = File(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), LEGACY_IMAGE_PARENT_DIR),
            folderName
        )
        return dcimFolder.takeIf { it.isDirectory }
    }

    private fun requiredMediaPermissions(needsImages: Boolean, needsVideos: Boolean): List<String> {
        if (!needsImages && !needsVideos) return emptyList()
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> buildList {
                if (needsImages) add(Manifest.permission.READ_MEDIA_IMAGES)
                if (needsVideos) add(Manifest.permission.READ_MEDIA_VIDEO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> emptyList()
        }
    }

    private fun missingMediaPermissions(needsImages: Boolean, needsVideos: Boolean): List<String> {
        return requiredMediaPermissions(needsImages, needsVideos)
            .filterNot(appContext::hasPermission)
    }

    private fun probeVideoPermissionRequirement(task: DownloadTaskData): VideoPermissionProbeResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return VideoPermissionProbeResult(needsPermission = true, permissionRequiredCount = 1)
        }
        if (videoDisplayNameCandidates(task.fileName).isEmpty()) {
            return VideoPermissionProbeResult(noSearchKeyCount = 1)
        }
        return when (val result = relocateVideo(task)) {
            is VideoRelocationResult.Relocated -> VideoPermissionProbeResult(visibleUniqueCandidateCount = 1)
            is VideoRelocationResult.Skipped -> when (result.reasonCode) {
                MediaRelocationReason.NO_CANDIDATE -> {
                    VideoPermissionProbeResult(needsPermission = true, permissionRequiredCount = 1)
                }
                else -> VideoPermissionProbeResult(notRecoverableCount = 1)
            }
        }
    }

    private fun probeImagePermissionRequirement(
        batch: ImageExtractBatchData,
        unreadableItems: List<ImageExtractItemData>,
    ): ImagePermissionProbeResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ImagePermissionProbeResult(
                needsPermission = true,
                permissionRequiredItemCount = unreadableItems.size
            )
        }
        val searchableItems = unreadableItems.filter { !it.finalName.isNullOrBlank() }
        val noSearchKeyItemCount = unreadableItems.size - searchableItems.size
        if (searchableItems.isEmpty()) {
            return ImagePermissionProbeResult(noSearchKeyItemCount = noSearchKeyItemCount)
        }
        val relativePath = normalizeImageOutputDir(batch.outputDir)
            ?: return ImagePermissionProbeResult(
                noSearchKeyItemCount = noSearchKeyItemCount,
                notRecoverableItemCount = searchableItems.size
            )
        val result = relocateMediaStoreImageBatch(relativePath, searchableItems)
        var visibleUniqueItemCount = 0
        var permissionRequiredItemCount = 0
        var notRecoverableItemCount = 0
        result.itemResults.forEach { itemResult ->
            when (itemResult) {
                is ImageItemRelocationResult.Relocated -> visibleUniqueItemCount++
                is ImageItemRelocationResult.Skipped -> {
                    if (itemResult.reasonCode in permissionRecoverableImageReasons) {
                        permissionRequiredItemCount++
                    } else {
                        notRecoverableItemCount++
                    }
                }
            }
        }
        return ImagePermissionProbeResult(
            needsPermission = permissionRequiredItemCount > 0,
            visibleUniqueItemCount = visibleUniqueItemCount,
            permissionRequiredItemCount = permissionRequiredItemCount,
            noSearchKeyItemCount = noSearchKeyItemCount,
            notRecoverableItemCount = notRecoverableItemCount
        )
    }

    private fun String?.toMediaRef(): MediaReference? {
        val raw = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val uri = runCatching { raw.toUri() }.getOrNull()
        return if (uri?.scheme == "content") {
            MediaReference(uri = uri)
        } else {
            MediaReference(path = raw)
        }
    }

    private fun MediaReference.isReadable(): Boolean {
        uri?.let { return it.isReadableContentUri() }
        return path?.let { File(it).exists() } == true
    }

    private fun Uri.isReadableContentUri(): Boolean {
        val queried = runCatching {
            val projection = buildList {
                add(MediaStore.MediaColumns._ID)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    add(MediaStore.MediaColumns.IS_TRASHED)
                }
            }.toTypedArray()
            appContext.contentResolver.query(this, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val trashedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
                    if (trashedIndex >= 0 && cursor.getInt(trashedIndex) != 0) return@use false
                }
                true
            }
        }.getOrNull()
        if (queried != null) return queried
        return runCatching {
            appContext.contentResolver.openAssetFileDescriptor(this, "r")?.use { true } == true
        }.getOrDefault(false)
    }

    private fun videoDisplayNameCandidates(fileName: String): Set<String> {
        val safeBaseName = fileName
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_")
            .trim()
            .take(80)
            .ifBlank { return emptySet() }
        return buildSet {
            add("$safeBaseName$VIDEO_EXTENSION")
            repeat(999) { index ->
                add("${safeBaseName}_${index + 1}$VIDEO_EXTENSION")
            }
        }
    }

    private fun DownloadTaskData.matchesSize(size: Long?): Boolean {
        val expected = totalSize.takeIf { it > 0L } ?: downloadedSize.takeIf { it > 0L } ?: return true
        return size == expected
    }

    private fun ImageExtractItemData.matchesDimensions(candidate: MediaCandidate): Boolean {
        val expectedWidth = width?.takeIf { it > 0 }
        val expectedHeight = height?.takeIf { it > 0 }
        if (expectedWidth != null && candidate.width != null && expectedWidth != candidate.width) return false
        if (expectedHeight != null && candidate.height != null && expectedHeight != candidate.height) return false
        return true
    }
}

data class MediaRelocationEstimate(
    val videoCount: Int,
    val imageBatchCount: Int,
    val imageItemCount: Int,
) {
    val totalCount: Int = videoCount + imageItemCount
    val durationLevel: MediaRelocationDurationLevel = when {
        totalCount == 0 -> MediaRelocationDurationLevel.None
        totalCount <= 30 -> MediaRelocationDurationLevel.Seconds
        totalCount <= 300 -> MediaRelocationDurationLevel.TensOfSeconds
        else -> MediaRelocationDurationLevel.Minutes
    }
}

enum class MediaRelocationDurationLevel(val logCode: String) {
    None("none"),
    Seconds("seconds"),
    TensOfSeconds("tens_of_seconds"),
    Minutes("minutes"),
}

data class MediaRelocationPreparation(
    val estimate: MediaRelocationEstimate,
    val needsImageScan: Boolean,
    val needsVideoScan: Boolean,
    val needsImagePermission: Boolean,
    val needsVideoPermission: Boolean,
    val requiredPermissions: List<String>,
    val existingReadableVideoCount: Int,
    val existingReadableImageCount: Int,
    val permissionRequiredVideoCount: Int,
    val permissionRequiredImageItemCount: Int,
) {
    val needsScan: Boolean = needsImageScan || needsVideoScan
}

data class MediaRelocationProgress(
    val stage: MediaRelocationStage,
    val processedVideos: Int,
    val totalVideos: Int,
    val processedImageBatches: Int,
    val totalImageBatches: Int,
    val processedImageItems: Int,
    val totalImageItems: Int,
    val relocatedCount: Int,
    val report: MediaRelocationReport,
)

enum class MediaRelocationStage(val logCode: String) {
    VerifyingExisting("verifying_existing"),
    ScanningVideos("scanning_videos"),
    ScanningImages("scanning_images"),
    Completed("completed"),
}

data class MediaRelocationReport(
    val video: MediaRelocationCategoryReport = MediaRelocationCategoryReport(),
    val image: MediaRelocationCategoryReport = MediaRelocationCategoryReport(),
    val imageFolderMissingBatches: Int = 0,
) {
    val totalRelocated: Int = video.relocated + image.relocated
    val totalExistingReadable: Int = video.existingReadable + image.existingReadable
}

data class MediaRelocationCategoryReport(
    val existingReadable: Int = 0,
    val relocated: Int = 0,
    val folderMissing: Int = 0,
    val noCandidate: Int = 0,
    val multipleCandidates: Int = 0,
    val metadataMismatch: Int = 0,
    val permissionDenied: Int = 0,
    val writeFailed: Int = 0,
)

object MediaRelocationReason {
    const val EXISTING_READABLE = "existing_readable"
    const val RELOCATED = "relocated"
    const val FOLDER_MISSING = "folder_missing"
    const val NO_CANDIDATE = "no_candidate"
    const val MULTIPLE_CANDIDATES = "multiple_candidates"
    const val METADATA_MISMATCH = "metadata_mismatch"
    const val PERMISSION_DENIED = "permission_denied"
    const val WRITE_FAILED = "write_failed"
}

private data class MediaReference(
    val uri: Uri? = null,
    val path: String? = null,
)

private data class MediaCandidate(
    val reference: String,
    val width: Int? = null,
    val height: Int? = null,
)

private data class ImageBatchRelocationResult(
    val folderMissingBatches: Int,
    val itemResults: List<ImageItemRelocationResult>,
)

private data class VideoPermissionProbeResult(
    val needsPermission: Boolean = false,
    val visibleUniqueCandidateCount: Int = 0,
    val permissionRequiredCount: Int = 0,
    val noSearchKeyCount: Int = 0,
    val notRecoverableCount: Int = 0,
)

private data class ImagePermissionProbeResult(
    val needsPermission: Boolean = false,
    val visibleUniqueItemCount: Int = 0,
    val permissionRequiredItemCount: Int = 0,
    val noSearchKeyItemCount: Int = 0,
    val notRecoverableItemCount: Int = 0,
)

private data class MediaRelocationPreparationDiagnostics(
    var unreadableVideoCount: Int = 0,
    var unreadableImageBatchCount: Int = 0,
    var unreadableImageItemCount: Int = 0,
    var visibleVideoCandidateCount: Int = 0,
    var visibleImageCandidateCount: Int = 0,
    var videoPermissionRequiredCount: Int = 0,
    var imagePermissionRequiredBatchCount: Int = 0,
    var imagePermissionRequiredItemCount: Int = 0,
    var videoNoSearchKeyCount: Int = 0,
    var imageNoSearchKeyItemCount: Int = 0,
    var videoNotRecoverableCount: Int = 0,
    var imageNotRecoverableItemCount: Int = 0,
)

private sealed class VideoRelocationResult {
    data class Relocated(val savePath: String) : VideoRelocationResult()
    data class Skipped(val reasonCode: String) : VideoRelocationResult()
}

private sealed class ImageItemRelocationResult {
    data class Relocated(val itemId: Long, val outputUri: String) : ImageItemRelocationResult()
    data class Skipped(val reasonCode: String) : ImageItemRelocationResult()
}

private data class MutableMediaRelocationProgress(
    val totalVideos: Int,
    val totalImageBatches: Int,
    val totalImageItems: Int,
    var processedVideos: Int = 0,
    var processedImageBatches: Int = 0,
    var processedImageItems: Int = 0,
    var relocatedCount: Int = 0,
) {
    fun toProgress(stage: MediaRelocationStage, report: MediaRelocationReport): MediaRelocationProgress {
        return MediaRelocationProgress(
            stage = stage,
            processedVideos = processedVideos,
            totalVideos = totalVideos,
            processedImageBatches = processedImageBatches,
            totalImageBatches = totalImageBatches,
            processedImageItems = processedImageItems,
            totalImageItems = totalImageItems,
            relocatedCount = relocatedCount,
            report = report
        )
    }
}

private data class MutableMediaRelocationReport(
    val video: MutableMediaRelocationCategoryReport = MutableMediaRelocationCategoryReport(),
    val image: MutableMediaRelocationCategoryReport = MutableMediaRelocationCategoryReport(),
    var imageFolderMissingBatches: Int = 0,
) {
    fun toReport(): MediaRelocationReport {
        return MediaRelocationReport(
            video = video.toReport(),
            image = image.toReport(),
            imageFolderMissingBatches = imageFolderMissingBatches
        )
    }
}

private data class MutableMediaRelocationCategoryReport(
    var existingReadable: Int = 0,
    var relocated: Int = 0,
    var folderMissing: Int = 0,
    var noCandidate: Int = 0,
    var multipleCandidates: Int = 0,
    var metadataMismatch: Int = 0,
    var permissionDenied: Int = 0,
    var writeFailed: Int = 0,
) {
    fun add(reasonCode: String) {
        when (reasonCode) {
            MediaRelocationReason.EXISTING_READABLE -> existingReadable++
            MediaRelocationReason.RELOCATED -> relocated++
            MediaRelocationReason.FOLDER_MISSING -> folderMissing++
            MediaRelocationReason.NO_CANDIDATE -> noCandidate++
            MediaRelocationReason.MULTIPLE_CANDIDATES -> multipleCandidates++
            MediaRelocationReason.METADATA_MISMATCH -> metadataMismatch++
            MediaRelocationReason.PERMISSION_DENIED -> permissionDenied++
            MediaRelocationReason.WRITE_FAILED -> writeFailed++
        }
    }

    fun toReport(): MediaRelocationCategoryReport {
        return MediaRelocationCategoryReport(
            existingReadable = existingReadable,
            relocated = relocated,
            folderMissing = folderMissing,
            noCandidate = noCandidate,
            multipleCandidates = multipleCandidates,
            metadataMismatch = metadataMismatch,
            permissionDenied = permissionDenied,
            writeFailed = writeFailed
        )
    }
}

private class MediaRelocationProgressLogger {
    private var lastLoggedCount = 0
    private var lastLoggedAt = System.currentTimeMillis()

    fun maybeLog(progress: MediaRelocationProgress) {
        val processed = progress.processedVideos + progress.processedImageItems
        val now = System.currentTimeMillis()
        if (processed - lastLoggedCount < PROGRESS_LOG_INTERVAL_COUNT && now - lastLoggedAt < PROGRESS_LOG_INTERVAL_MS) {
            return
        }
        lastLoggedCount = processed
        lastLoggedAt = now
        logD(TAG) {
            "媒体重新定位进度 stage=${progress.stage.logCode} processedVideos=${progress.processedVideos}/${progress.totalVideos} " +
                "processedImageBatches=${progress.processedImageBatches}/${progress.totalImageBatches} " +
                "processedImageItems=${progress.processedImageItems}/${progress.totalImageItems} relocated=${progress.relocatedCount}"
        }
    }
}
