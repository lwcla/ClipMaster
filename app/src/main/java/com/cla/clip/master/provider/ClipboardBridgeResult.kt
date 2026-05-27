package com.cla.clip.master.provider

import android.os.Bundle
import com.cla.clip.shizuku.ClipboardBridgeContract

/**
 * Provider 读取剪贴板后的结构化结果。
 *
 * Shizuku 侧会解析 `content call` 打印出的 Bundle，所以字段名必须保持稳定。
 */
data class ClipboardBridgeResult(
    /** 结构化结果码，用于区分成功、权限拒绝、参数错误和系统能力失败。 */
    val resultCode: String,
    /** 是否已经读取并保存剪贴记录。 */
    val saved: Boolean,
    /** 是否读取到了剪贴板 item。 */
    val readClip: Boolean,
    /** 是否成功添加过透明悬浮窗。 */
    val overlayAdded: Boolean,
    /** 图标处理状态，取值来自 ClipboardBridgeContract 的 ICON_STATUS_*。 */
    val iconStatus: String,
    /** read_clip 是否有图标待异步补齐；commit_icon 固定为 false。 */
    val iconDeferred: Boolean,
) {
    companion object {
        /**
         * 构建标准结果对象。
         *
         * @param resultCode Provider 结构化结果码。
         * @param saved 是否已经完成入库。
         * @param readClip 是否读取到剪贴板内容。
         * @param overlayAdded 是否成功添加悬浮窗。
         * @param iconStatus 图标处理状态。
         * @param iconDeferred read_clip 是否有图标待异步补齐。
         */
        fun of(
            resultCode: String,
            saved: Boolean = false,
            readClip: Boolean = false,
            overlayAdded: Boolean = false,
            iconStatus: String = ClipboardBridgeContract.ICON_STATUS_PLACEHOLDER,
            iconDeferred: Boolean = false,
        ): ClipboardBridgeResult {
            return ClipboardBridgeResult(
                resultCode = resultCode,
                saved = saved,
                readClip = readClip,
                overlayAdded = overlayAdded,
                iconStatus = iconStatus,
                iconDeferred = iconDeferred
            )
        }
    }

    /** 转成 Provider `call` 返回的 Bundle，供 shell `content call` 打印。 */
    fun toBundle(): Bundle {
        return Bundle().apply {
            putString(ClipboardBridgeContract.RESULT_CODE, resultCode)
            putBoolean(ClipboardBridgeContract.RESULT_SAVED, saved)
            putBoolean(ClipboardBridgeContract.RESULT_READ_CLIP, readClip)
            putBoolean(ClipboardBridgeContract.RESULT_OVERLAY_ADDED, overlayAdded)
            putString(ClipboardBridgeContract.RESULT_ICON_STATUS, iconStatus)
            putBoolean(ClipboardBridgeContract.RESULT_ICON_DEFERRED, iconDeferred)
        }
    }
}
