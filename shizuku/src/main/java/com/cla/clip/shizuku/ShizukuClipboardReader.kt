package com.cla.clip.shizuku

import android.content.ClipData
import com.cla.clip.base.hidden.api.SystemClipboardHiddenReader
import com.cla.clip.base.general.utils.logE

/**
 * Shizuku/shell 进程内的系统剪贴板读取器。
 *
 * 该类只选择 Shizuku/shell 读取身份并完成 ClipData 到 payload 的映射，不负责底层隐藏 `IClipboard` Binder 调用、Provider 提交、图标同步或数据库入库。
 */
internal class ShizukuClipboardReader {
    companion object {
        /** 读取器日志标签，用于定位隐藏 API 签名和 Binder 调用失败。 */
        private const val TAG = "ShizukuClipboardReader"

        /** shell 进程读取系统剪贴板时使用的调用包名，必须与 Shizuku/shell uid 匹配。 */
        internal const val SHELL_CLIPBOARD_CALLING_PACKAGE = "com.android.shell"

        /** 默认用户 ID；当前正式链路只读取主用户剪贴板。 */
        private const val DEFAULT_CLIPBOARD_USER_ID = 0
    }

    /** 系统剪贴板隐藏 API 读取器；封装 ServiceManager/IClipboard 签名差异和隐藏 API 豁免。 */
    private val systemClipboardReader = SystemClipboardHiddenReader()

    /**
     * 读取系统当前主剪贴板。
     *
     * @param userId 要读取的 Android 用户 ID；失败时返回 null 并只输出脱敏错误日志。
     */
    fun readPrimaryClip(userId: Int = DEFAULT_CLIPBOARD_USER_ID): ClipData? {
        return runCatching {
            systemClipboardReader.readPrimaryClip(
                callingPackage = SHELL_CLIPBOARD_CALLING_PACKAGE,
                userId = userId
            )
        }.onFailure { throwable ->
            logE(TAG, throwable) { "Shizuku 进程直读系统剪贴板失败" }
        }.getOrNull()
    }

    /**
     * 将系统 ClipData 映射成 Provider payload。
     *
     * @param clipData Shizuku 进程读到的系统剪贴板快照；为空时返回 null，让上层决定是否触发调试回退。
     * @param eventId 当前剪贴事件 ID，必须和 Provider 临时文件名一致。
     * @param capturedAtMillis 捕获时间戳，用于避免并发提交改变列表顺序。
     */
    fun toPayload(
        clipData: ClipData?,
        eventId: String,
        capturedAtMillis: Long,
    ): ClipboardBridgeClipPayload? {
        if (clipData == null) {
            return null
        }

        /** 首个剪贴 item；第一版只处理单 item，后续多 item 支持再扩展协议。 */
        val firstItem = clipData.takeIf { data -> data.itemCount > 0 }?.getItemAt(0)

        /** 首个 item 的普通文本；空白文本交给 app 侧按空内容或 HTML fallback 处理。 */
        val text = firstItem?.text?.toString()

        /** 首个 item 的 HTML 文本；app 侧只会转纯文本，不持久化原始 HTML。 */
        val htmlText = firstItem?.htmlText

        /** ClipDescription 中的 MIME 类型列表；用于 app 侧区分空剪贴和不支持类型。 */
        val mimeTypes = clipData.description
            ?.let { description ->
                List(description.mimeTypeCount) { index -> description.getMimeType(index) }
            }
            .orEmpty()

        return ClipboardBridgeClipPayload(
            version = ClipboardBridgeContract.CLIP_PAYLOAD_VERSION,
            eventId = eventId,
            capturedAtMillis = capturedAtMillis,
            mimeTypes = mimeTypes,
            text = text,
            htmlText = htmlText
        )
    }
}
