package com.cla.clip.master.provider

import android.os.Bundle
import com.cla.clip.shizuku.ClipboardBridgeContract

/**
 * Provider 剪贴板和图标桥接后的结构化结果。
 *
 * Shizuku 侧会解析 `content call` 打印出的 Bundle，所以字段名必须保持稳定。
 */
data class ClipboardBridgeResult(
    /** 结构化结果码，用于区分成功、权限拒绝、参数错误和系统能力失败。 */
    val resultCode: String,
    /** 是否已经读取并保存剪贴记录。 */
    val saved: Boolean,
    /** commit_clip 是否真实写入或更新了一条剪贴记录。 */
    val clipCommitted: Boolean,
    /** commit_clip 的剪贴处理状态，不包含正文。 */
    val clipStatus: String?,
    /** commit_clip 解析到的普通文本长度，仅用于脱敏诊断。 */
    val textLength: Int?,
    /** commit_clip 解析到的 HTML 长度，仅用于脱敏诊断。 */
    val htmlLength: Int?,
    /** commit_clip 解析到的 MIME 类型列表，仅用于类型诊断。 */
    val mimeTypes: List<String>,
    /** 是否读取到了剪贴板 item。 */
    val readClip: Boolean,
    /** 是否成功添加过透明悬浮窗。 */
    val overlayAdded: Boolean,
    /** 图标处理状态，取值来自 ClipboardBridgeContract 的 ICON_STATUS_*。 */
    val iconStatus: String,
    /** query_icon_state 是否要求调用方继续同步来源图标。 */
    val shouldSyncIcon: Boolean?,
    /** 图标同步决策原因；仅 query_icon_state 必填。 */
    val iconDecisionReason: String?,
) {
    companion object {
        /**
         * 构建标准结果对象。
         *
         * @param resultCode Provider 结构化结果码。
         * @param saved 是否已经完成入库。
         * @param clipCommitted commit_clip 是否真实写入或更新剪贴记录。
         * @param clipStatus commit_clip 的剪贴处理状态。
         * @param textLength commit_clip 解析到的普通文本长度。
         * @param htmlLength commit_clip 解析到的 HTML 长度。
         * @param mimeTypes commit_clip 解析到的 MIME 类型列表。
         * @param readClip 是否读取到剪贴板内容。
         * @param overlayAdded 是否成功添加悬浮窗。
         * @param iconStatus 图标处理状态。
         * @param shouldSyncIcon query_icon_state 是否要求继续同步来源图标。
         * @param iconDecisionReason 本次图标决策原因。
         */
        fun of(
            resultCode: String,
            saved: Boolean = false,
            clipCommitted: Boolean = false,
            clipStatus: String? = null,
            textLength: Int? = null,
            htmlLength: Int? = null,
            mimeTypes: List<String> = emptyList(),
            readClip: Boolean = false,
            overlayAdded: Boolean = false,
            iconStatus: String = ClipboardBridgeContract.ICON_STATUS_PLACEHOLDER,
            shouldSyncIcon: Boolean? = null,
            iconDecisionReason: String? = null,
        ): ClipboardBridgeResult {
            return ClipboardBridgeResult(
                resultCode = resultCode,
                saved = saved,
                clipCommitted = clipCommitted,
                clipStatus = clipStatus,
                textLength = textLength,
                htmlLength = htmlLength,
                mimeTypes = mimeTypes,
                readClip = readClip,
                overlayAdded = overlayAdded,
                iconStatus = iconStatus,
                shouldSyncIcon = shouldSyncIcon,
                iconDecisionReason = iconDecisionReason
            )
        }
    }

    /** 转成 Provider `call` 返回的 Bundle，供 shell `content call` 打印。 */
    fun toBundle(): Bundle {
        return Bundle().apply {
            putString(ClipboardBridgeContract.RESULT_CODE, resultCode)
            putBoolean(ClipboardBridgeContract.RESULT_SAVED, saved)
            putBoolean(ClipboardBridgeContract.RESULT_CLIP_COMMITTED, clipCommitted)
            clipStatus?.let { putString(ClipboardBridgeContract.RESULT_CLIP_STATUS, it) }
            textLength?.let { putInt(ClipboardBridgeContract.RESULT_TEXT_LENGTH, it) }
            htmlLength?.let { putInt(ClipboardBridgeContract.RESULT_HTML_LENGTH, it) }
            if (mimeTypes.isNotEmpty()) {
                putStringArrayList(ClipboardBridgeContract.RESULT_MIME_TYPES, ArrayList(mimeTypes))
            }
            putBoolean(ClipboardBridgeContract.RESULT_READ_CLIP, readClip)
            putBoolean(ClipboardBridgeContract.RESULT_OVERLAY_ADDED, overlayAdded)
            putString(ClipboardBridgeContract.RESULT_ICON_STATUS, iconStatus)
            shouldSyncIcon?.let { putBoolean(ClipboardBridgeContract.RESULT_SHOULD_SYNC_ICON, it) }
            iconDecisionReason?.let { putString(ClipboardBridgeContract.RESULT_ICON_DECISION_REASON, it) }
        }
    }
}
