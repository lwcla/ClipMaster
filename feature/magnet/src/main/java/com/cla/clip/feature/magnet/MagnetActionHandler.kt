package com.cla.clip.feature.magnet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.cla.clip.feature.magnet.R
import com.cla.clip.feature.magnet.data.MagnetDownloadRecordData
import com.cla.clip.feature.magnet.source.cache.MagnetSearchResult
import com.cla.clip.feature.magnet.data.MagnetRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 磁力复制/打开动作处理器，统一搜索页和下载记录页对用户磁力记录的副作用。 */
@Singleton
class MagnetActionHandler @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val magnetRepository: MagnetRepository,
) {
    private val clipboardManager: ClipboardManager by lazy {
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    /** 复制搜索结果并尝试交给外部下载器打开，同时写入磁力记录。 */
    suspend fun copyAndOpenSearchResult(
        result: MagnetSearchResult,
        sourceQuery: String?,
    ): MagnetActionResult {
        val record = magnetRepository.recordMagnetUse(
            sourceId = result.sourceId,
            infoHash = result.infoHash,
            title = result.title,
            detailUrl = result.detailUrl,
            sizeBytes = result.sizeBytes,
            category = result.category,
            magnetUri = result.magnetUri,
            sourceQuery = sourceQuery,
        ) ?: return MagnetActionResult.Invalid
        return copyAndOpen(record.magnetUri)
    }

    /** 只复制搜索结果，同时写入磁力记录，供用户在没有默认下载器时手动粘贴。 */
    suspend fun copySearchResult(
        result: MagnetSearchResult,
        sourceQuery: String?,
    ): MagnetActionResult {
        val record = magnetRepository.recordMagnetUse(
            sourceId = result.sourceId,
            infoHash = result.infoHash,
            title = result.title,
            detailUrl = result.detailUrl,
            sizeBytes = result.sizeBytes,
            category = result.category,
            magnetUri = result.magnetUri,
            sourceQuery = sourceQuery,
        ) ?: return MagnetActionResult.Invalid
        copyToClipboard(record.magnetUri)
        return MagnetActionResult.CopiedOnly
    }

    /** 从下载记录页复制并打开磁力，刷新最近使用时间。 */
    suspend fun copyAndOpenRecord(recordId: Long): MagnetActionResult {
        val record = magnetRepository.touchDownloadRecord(recordId) ?: return MagnetActionResult.Invalid
        return copyAndOpen(record.magnetUri)
    }

    /** 从下载记录页只复制磁力，刷新最近使用时间。 */
    suspend fun copyRecord(recordId: Long): MagnetActionResult {
        val record = magnetRepository.touchDownloadRecord(recordId) ?: return MagnetActionResult.Invalid
        copyToClipboard(record.magnetUri)
        return MagnetActionResult.CopiedOnly
    }

    /** 已有记录对象的轻量复制打开入口；用于调用方已经取到实体时避免重复传输标题等敏感文本。 */
    suspend fun copyAndOpenRecord(record: MagnetDownloadRecordData): MagnetActionResult {
        magnetRepository.touchDownloadRecord(record.id)
        return copyAndOpen(record.magnetUri)
    }

    private fun copyAndOpen(magnetUri: String): MagnetActionResult {
        copyToClipboard(magnetUri)
        val opened = runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(magnetUri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
        }.isSuccess
        return if (opened) {
            MagnetActionResult.CopiedAndOpened
        } else {
            MagnetActionResult.CopiedNoApp
        }
    }

    private fun copyToClipboard(magnetUri: String) {
        clipboardManager.setPrimaryClip(
            ClipData.newPlainText(
                appContext.getString(R.string.magnet_feature_magnet_clipboard_label),
                magnetUri
            )
        )
    }
}

/** 磁力动作结果只返回用户提示语义，不携带磁力 URI 或搜索关键词，避免日志和状态泄露。 */
enum class MagnetActionResult {
    CopiedAndOpened,
    CopiedOnly,
    CopiedNoApp,
    Invalid,
}
