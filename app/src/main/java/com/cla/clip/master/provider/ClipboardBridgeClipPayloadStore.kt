package com.cla.clip.master.provider

import android.content.Context
import android.os.ParcelFileDescriptor
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logW
import com.cla.clip.shizuku.ClipboardBridgeClipPayload
import com.cla.clip.shizuku.ClipboardBridgeContract
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject

/**
 * Provider 剪贴 payload 临时传输目录管理器。
 *
 * Shizuku 通过 `content write` 把 JSON payload 写入这里，`commit_clip` 再按 eventId 精确消费并清理自己的临时文件。
 */
class ClipboardBridgeClipPayloadStore @Inject constructor() {
    companion object {
        /** Provider 剪贴 payload 临时目录名，必须同步排除系统 Auto Backup。 */
        const val TEMP_CLIP_PAYLOAD_DIR = "clipboard_bridge_clip_payloads"

        /** `content write` 使用的临时文件后缀，commit_clip 只消费匹配 eventId 的半文件。 */
        private const val CLIP_PAYLOAD_TEMP_SUFFIX = ".tmp"

        /** 临时 payload 最长保留时间；超过该时间的文件在下一次 Provider 调用时清理。 */
        private const val TEMP_CLIP_PAYLOAD_TTL_MS = 10 * 60 * 1000L

        /** payload store 日志标签，用于定位 content write 和清理行为。 */
        private const val TAG = "ClipboardBridgeClipPayloadStore"

        /** eventId 文件名安全正则，禁止路径穿越和超长文件名。 */
        private val SAFE_EVENT_ID_REGEX = Regex("[A-Za-z0-9._-]{1,80}")
    }

    /**
     * 为 `content write` 打开当前事件的 payload 临时文件。
     *
     * @param context 应用 Context，用于定位私有 files 目录。
     * @param eventId 当前剪贴事件 ID，已经由 Provider 路径解析并校验。
     */
    fun openPayloadForWrite(context: Context, eventId: String): ParcelFileDescriptor {
        /** Provider payload 临时目录；不存在时由当前调用创建。 */
        val dir = tempDir(context)

        /** 当前事件的临时 payload 文件；MODE_TRUNCATE 保证重试写入不会混入旧内容。 */
        val file = tempPayloadFile(dir, eventId)
        return ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY
        )
    }

    /**
     * 读取并解析当前事件的 payload。
     *
     * @param context 应用 Context，用于定位私有临时目录。
     * @param eventId 当前剪贴事件 ID，只消费同名半文件。
     */
    fun readPayload(context: Context, eventId: String): ClipboardBridgeClipPayloadReadResult {
        cleanupExpired(context)

        /** Provider payload 临时目录，所有事件 payload 都按 eventId 独立存放。 */
        val dir = tempDir(context)
        /** 当前 eventId 对应的临时文件；缺失说明 content write 未完成或写入失败。 */
        val file = tempPayloadFile(dir, eventId)
        if (!file.exists() || file.length() <= 0L) {
            logW(TAG) { "Provider 剪贴 payload 缺失 eventId=$eventId" }
            file.delete()
            return ClipboardBridgeClipPayloadReadResult.Missing
        }

        return runCatching {
            /** payload JSON 文本；属于敏感内容，禁止进入日志。 */
            val jsonText = file.readText(Charsets.UTF_8)
            /** 解析后的 payload；版本和 eventId 还需要继续校验。 */
            val payload = ClipboardBridgeClipPayload.fromJsonString(jsonText)
            if (!isPayloadAccepted(payload, eventId)) {
                logW(TAG) {
                    "Provider 剪贴 payload 校验失败 eventId=$eventId payloadEventId=${payload.eventId} version=${payload.version}"
                }
                ClipboardBridgeClipPayloadReadResult.Invalid
            } else {
                logD(TAG) {
                    "Provider 剪贴 payload 读取成功 eventId=$eventId size=${file.length()} " +
                        "textLength=${payload.text?.length} htmlLength=${payload.htmlText?.length} mimeTypes=${payload.mimeTypes}"
                }
                ClipboardBridgeClipPayloadReadResult.Read(payload)
            }
        }.getOrElse { throwable ->
            logW(TAG) { "Provider 剪贴 payload 解析失败 eventId=$eventId error=${throwable::class.java.simpleName}" }
            ClipboardBridgeClipPayloadReadResult.Invalid
        }
    }

    /**
     * 删除当前事件的 payload 临时文件。
     *
     * @param context 应用 Context，用于定位私有临时目录。
     * @param eventId 当前剪贴事件 ID；只删除自己的文件，避免误删并发 payload。
     */
    fun deletePayload(context: Context, eventId: String) {
        /** 当前事件 payload 文件；提交成功、失败或异常后都只清理它自己。 */
        val file = tempPayloadFile(File(context.filesDir, TEMP_CLIP_PAYLOAD_DIR), eventId)
        if (file.exists() && file.delete()) {
            logD(TAG) { "Provider 剪贴 payload 已清理 eventId=$eventId" }
        }
    }

    /**
     * 清理过期临时 payload。
     *
     * @param context 应用 Context，用于定位临时目录。
     */
    fun cleanupExpired(context: Context) {
        /** Provider payload 临时目录；不存在时无须清理。 */
        val dir = File(context.filesDir, TEMP_CLIP_PAYLOAD_DIR)
        cleanupExpiredFiles(dir, System.currentTimeMillis())
    }

    /**
     * 清理指定目录中超过 TTL 的临时 payload 文件。
     *
     * @param dir Provider payload 临时目录。
     * @param nowMillis 当前时间戳，测试可传入固定值避免依赖系统时间。
     */
    internal fun cleanupExpiredFiles(dir: File, nowMillis: Long): Int {
        if (!dir.exists()) {
            return 0
        }

        /** 本次成功删除的过期 payload 数量，用于测试和后续诊断扩展。 */
        var deletedCount = 0
        dir.listFiles()?.forEach { file ->
            /** 单个临时文件是否超过 TTL；目录项和非 payload 后缀不删除，避免影响并发写入。 */
            val expired = file.isFile && isBridgeClipPayloadTempFile(file) &&
                nowMillis - file.lastModified() > TEMP_CLIP_PAYLOAD_TTL_MS
            if (expired && file.delete()) {
                deletedCount += 1
            }
        }
        return deletedCount
    }

    /**
     * 从剪贴 payload URI 路径中解析 eventId。
     *
     * @param pathSegments Provider URI 的路径段。
     */
    fun parseEventId(pathSegments: List<String>): String {
        if (pathSegments.size != 2 || pathSegments[0] != ClipboardBridgeContract.PATH_CLIP) {
            throw FileNotFoundException("Unsupported clip path: $pathSegments")
        }

        /** clip 路径里的事件 ID；只允许文件名安全字符。 */
        val eventId = pathSegments[1]
        if (!SAFE_EVENT_ID_REGEX.matches(eventId)) {
            throw FileNotFoundException("Invalid eventId: $eventId")
        }
        return eventId
    }

    /**
     * 判断 payload 是否符合当前协议和事件边界。
     *
     * @param payload 从 JSON 解析出的剪贴 payload。
     * @param expectedEventId Provider 路径中的事件 ID，必须和 payload 内部一致。
     */
    internal fun isPayloadAccepted(payload: ClipboardBridgeClipPayload, expectedEventId: String): Boolean {
        return payload.version == ClipboardBridgeContract.CLIP_PAYLOAD_VERSION &&
            payload.eventId == expectedEventId &&
            payload.capturedAtMillis > 0L
    }

    /**
     * 返回 payload 临时目录，并确保目录存在。
     *
     * @param context 应用 Context，用于定位 filesDir。
     */
    private fun tempDir(context: Context): File {
        /** 私有临时目录，不跨安装保留，不参与备份。 */
        val dir = File(context.filesDir, TEMP_CLIP_PAYLOAD_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 根据 eventId 构造 `content write` 使用的 payload 半文件路径。
     *
     * @param dir Provider payload 临时目录。
     * @param eventId 当前事件 ID。
     */
    private fun tempPayloadFile(dir: File, eventId: String): File {
        return File(dir, eventId + CLIP_PAYLOAD_TEMP_SUFFIX)
    }

    /**
     * 判断目录项是否属于 Provider 剪贴 payload 临时文件。
     *
     * @param file Provider payload 目录中的单个文件。
     */
    private fun isBridgeClipPayloadTempFile(file: File): Boolean {
        return file.name.endsWith(CLIP_PAYLOAD_TEMP_SUFFIX)
    }
}

/**
 * Provider payload 读取结果。
 *
 * 使用 sealed result 明确缺失、畸形和成功三种状态，避免调用方依赖异常控制正常失败契约。
 */
sealed interface ClipboardBridgeClipPayloadReadResult {
    /** payload 文件缺失或为空，通常表示 content write 未完成。 */
    object Missing : ClipboardBridgeClipPayloadReadResult

    /** payload 文件存在但 JSON、版本或 eventId 校验失败。 */
    object Invalid : ClipboardBridgeClipPayloadReadResult

    /**
     * payload 已成功解析。
     *
     * @param payload Provider 可继续提交的剪贴 payload。
     */
    data class Read(val payload: ClipboardBridgeClipPayload) : ClipboardBridgeClipPayloadReadResult
}
