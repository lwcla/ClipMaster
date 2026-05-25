package com.cla.clip.base.general.backup

import java.io.File

/** 一次导出的完整结果，调用方负责写入本地文件或上传 WebDAV。 */
data class BackupExportResult(
    /** 完整 zip 备份包临时文件。 */
    val packageFile: File,
    /** 列表 sidecar manifest。 */
    val manifest: BackupManifest,
    /** manifest JSON。 */
    val manifestJson: String,
    /** 快照文件名。 */
    val fileName: String,
    /** manifest 文件名。 */
    val manifestFileName: String,
    /** 本次导出的临时目录；调用方发布完成后可以清理。 */
    val taskDir: File,
) {
    /** 备份包大小，单位字节。 */
    val fileSize: Long
        get() = packageFile.length()
}

/** 流式导出开始时记录的 high-water mark，避免长事务锁库。 */
internal data class BackupHighWaterMarks(
    val clipMaxId: Long,
    val searchHistoryMaxId: Long,
    val videoDownloadMaxId: Long,
    val imageBatchMaxId: Long,
    val imageItemMaxId: Long,
    val featureMarks: Map<String, Map<String, Long>> = emptyMap(),
)

/** 导出过程中累积的数量摘要，最终会写入 manifest。 */
internal data class BackupSummaryBuilder(
    var clipCount: Int = 0,
    var sourceAppCount: Int = 0,
    var linkPreviewCount: Int = 0,
    var searchHistoryCount: Int = 0,
    var videoDownloadCount: Int = 0,
    var imageBatchCount: Int = 0,
    var imageItemCount: Int = 0,
    val featureCounts: MutableMap<String, Int> = linkedMapOf(),
) {
    /** 转为稳定外部协议中的摘要模型。 */
    fun toSummary(): BackupSummary {
        return BackupSummary(
            clipCount = clipCount,
            sourceAppCount = sourceAppCount,
            linkPreviewCount = linkPreviewCount,
            searchHistoryCount = searchHistoryCount,
            videoDownloadCount = videoDownloadCount,
            imageBatchCount = imageBatchCount,
            imageItemCount = imageItemCount,
            featureCounts = featureCounts.toSortedMap()
        )
    }
}
