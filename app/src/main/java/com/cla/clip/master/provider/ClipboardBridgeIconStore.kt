package com.cla.clip.master.provider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logW
import com.cla.clip.base.general.utils.saveIcon
import com.cla.clip.base.general.utils.toStableHash
import com.cla.clip.shizuku.ClipboardBridgeContract
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject

/**
 * Provider 图标临时传输目录管理器。
 *
 * Shizuku 通过 `content write` 把 PNG 写入这里，Provider 读取剪贴板时再校验 hash 并搬到正式来源图标目录。
 */
class ClipboardBridgeIconStore @Inject constructor() {
    companion object {
        /** Provider 图标临时目录名，必须同步排除系统 Auto Backup。 */
        const val TEMP_ICON_DIR = "clipboard_bridge_icons"

        /** 临时写入完成后的图标文件后缀，固定为 PNG 便于人工排查目录内容。 */
        private const val ICON_FILE_SUFFIX = ".png"

        /** `content write` 使用的临时半文件后缀，commit_icon 校验通过后再改名为 PNG。 */
        private const val ICON_TEMP_SUFFIX = ".tmp"

        /** 临时图标最长保留时间；超过该时间的文件在下一次 Provider 调用时清理。 */
        private const val TEMP_ICON_TTL_MS = 10 * 60 * 1000L

        /** 图标存储日志标签，用于定位 content write 和清理行为。 */
        private const val TAG = "ClipboardBridgeIconStore"
    }

    /**
     * 为 `content write` 打开临时图标文件。
     *
     * @param context 应用 Context，用于定位私有 files 目录。
     * @param eventId 当前事件 ID，已经由 Provider 路径解析并校验。
     */
    fun openIconForWrite(context: Context, eventId: String): ParcelFileDescriptor {
        /** Provider 图标临时目录；不存在时由当前调用创建。 */
        val dir = tempDir(context)

        /** 当前事件的临时半文件；MODE_TRUNCATE 保证重试写入不会混入旧内容。 */
        val file = tempIconFile(dir, eventId)
        return ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY
        )
    }

    /**
     * 提交并保存异步传入的图标。
     *
     * @param context 应用 Context，用于读取临时目录和保存正式图标。
     * @param request Provider commit_icon 请求参数。
     */
    fun commitIcon(context: Context, request: ClipboardBridgeRequest): ClipboardBridgeIconResolution {
        cleanupExpired(context)

        if (request.packageName.isNullOrBlank() || request.iconHash.isNullOrBlank()) {
            logW(TAG) { "Provider 图标提交参数无效 eventId=${request.eventId} packageName=${request.packageName}" }
            return ClipboardBridgeIconResolution.placeholder()
        }

        /** Provider 图标临时目录，半文件和正式临时 PNG 都保存在这里。 */
        val dir = tempDir(context)

        /** content write 写入的半文件，只有 commit_icon 会尝试消费。 */
        val tempFile = tempIconFile(dir, request.eventId)
        if (!tempFile.exists() || tempFile.length() <= 0L) {
            logW(TAG) { "Provider 图标半文件缺失 eventId=${request.eventId} packageName=${request.packageName}" }
            tempFile.delete()
            return ClipboardBridgeIconResolution.placeholder()
        }

        /** commit_icon 校验阶段使用的 PNG 文件，避免直接 decode 仍可能被写入中的半文件。 */
        val iconFile = iconFile(dir, request.eventId)
        if (!renameTempToIcon(tempFile, iconFile)) {
            logW(TAG) { "Provider 图标半文件转正失败 eventId=${request.eventId} packageName=${request.packageName}" }
            tempFile.delete()
            iconFile.delete()
            return ClipboardBridgeIconResolution.placeholder()
        }

        return saveVerifiedIcon(context, request, iconFile)
    }

    /**
     * 读取并保存当前事件图标。
     *
     * @param context 应用 Context，用于读取临时目录和写入正式图标目录。
     * @param request Provider read_clip 请求参数。
     * @param cachedIconPath 数据库中同包名同 hash 的旧图标路径，可为空。
     */
    fun resolveIcon(
        context: Context,
        request: ClipboardBridgeRequest,
        cachedIconPath: String?,
    ): ClipboardBridgeIconResolution {
        cleanupExpired(context)

        if (!request.iconHash.isNullOrBlank() && !cachedIconPath.isNullOrBlank()) {
            return ClipboardBridgeIconResolution(
                iconPath = cachedIconPath,
                iconColor = null,
                iconHash = request.iconHash,
                bitmap = null,
                status = ClipboardBridgeContract.ICON_STATUS_REUSED
            )
        }

        /** 当前事件图标临时文件；不存在说明 content write 失败或被跳过。 */
        val file = iconFile(tempDir(context), request.eventId)
        if (!file.exists()) {
            logW(TAG) { "Provider 图标缺失 eventId=${request.eventId} packageName=${request.packageName}" }
            return ClipboardBridgeIconResolution.placeholder()
        }

        return saveVerifiedIcon(context, request, file)
    }

    /**
     * 校验并保存已经落盘的 PNG 图标。
     *
     * @param context 应用 Context，用于写入正式来源图标目录。
     * @param request Provider 图标请求参数。
     * @param file 已经转正的临时 PNG 文件。
     */
    private fun saveVerifiedIcon(
        context: Context,
        request: ClipboardBridgeRequest,
        file: File,
    ): ClipboardBridgeIconResolution {
        /** 解码后的图标 Bitmap；解码失败时按占位图策略继续。 */
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap == null) {
            logW(TAG) { "Provider 图标解码失败 eventId=${request.eventId} size=${file.length()}" }
            file.delete()
            return ClipboardBridgeIconResolution.placeholder()
        }

        /** 本地重新计算的图标 hash，用于确认 content write 内容和 Shizuku 传入 hash 一致。 */
        val actualHash = bitmap.toStableHash()
        if (!isHashAccepted(request.iconHash, actualHash)) {
            logW(TAG) {
                "Provider 图标 hash 不匹配 eventId=${request.eventId} expected=${request.iconHash} actual=$actualHash size=${file.length()}"
            }
            file.delete()
            return ClipboardBridgeIconResolution.placeholder()
        }

        /** 正式保存后的来源图标路径；保存失败时按占位图继续，避免写入错误 hash。 */
        val iconPath = context.saveIcon(request.packageName, bitmap)
        if (iconPath.isNullOrBlank()) {
            logW(TAG) { "Provider 图标正式保存失败 eventId=${request.eventId} packageName=${request.packageName}" }
            file.delete()
            return ClipboardBridgeIconResolution.placeholder()
        }

        /** 临时图标文件大小；删除前记录，便于排查 content write 是否写入了有效数据。 */
        val iconSize = file.length()
        file.delete()
        logD(TAG) {
            "Provider 图标保存成功 eventId=${request.eventId} packageName=${request.packageName} size=$iconSize hash=$actualHash"
        }
        return ClipboardBridgeIconResolution(
            iconPath = iconPath,
            iconColor = null,
            iconHash = request.iconHash ?: actualHash,
            bitmap = bitmap,
            status = ClipboardBridgeContract.ICON_STATUS_SAVED
        )
    }

    /**
     * 清理过期临时图标。
     *
     * @param context 应用 Context，用于定位临时目录。
     */
    fun cleanupExpired(context: Context) {
        /** Provider 图标临时目录；不存在时无须清理。 */
        val dir = File(context.filesDir, TEMP_ICON_DIR)
        cleanupExpiredFiles(dir, System.currentTimeMillis())
    }

    /**
     * 清理指定目录中超过 TTL 的临时图标文件。
     *
     * @param dir Provider 图标临时目录。
     * @param nowMillis 当前时间戳，测试可传入固定值避免依赖系统时间。
     */
    internal fun cleanupExpiredFiles(dir: File, nowMillis: Long): Int {
        if (!dir.exists()) {
            return 0
        }

        /** 本次成功删除的过期临时图标数量，用于测试和后续诊断扩展。 */
        var deletedCount = 0
        dir.listFiles()?.forEach { file ->
            /** 单个临时文件是否超过 TTL；目录项异常时不删除，避免误删其他内容。 */
            val expired = file.isFile && isBridgeIconTempFile(file) && nowMillis - file.lastModified() > TEMP_ICON_TTL_MS
            if (expired && file.delete()) {
                deletedCount += 1
            }
        }
        return deletedCount
    }

    /**
     * 判断 Provider 图标 hash 是否可接受。
     *
     * @param expectedHash Shizuku 侧随 `content call` 传入的期望 hash，空值表示本次不校验。
     * @param actualHash Provider 侧从临时 PNG 重新计算出的真实 hash。
     */
    internal fun isHashAccepted(expectedHash: String?, actualHash: String): Boolean {
        return expectedHash.isNullOrBlank() || expectedHash == actualHash
    }

    /**
     * 从图标 URI 路径中解析 eventId。
     *
     * @param pathSegments Provider URI 的路径段。
     */
    fun parseEventId(pathSegments: List<String>): String {
        if (pathSegments.size != 2 || pathSegments[0] != ClipboardBridgeContract.PATH_ICON) {
            throw FileNotFoundException("Unsupported icon path: $pathSegments")
        }

        /** 图标路径里的事件 ID；只允许文件名安全字符。 */
        val eventId = pathSegments[1]
        if (!Regex("[A-Za-z0-9._-]{1,80}").matches(eventId)) {
            throw FileNotFoundException("Invalid eventId: $eventId")
        }
        return eventId
    }

    /**
     * 返回图标临时目录，并确保目录存在。
     *
     * @param context 应用 Context，用于定位 filesDir。
     */
    private fun tempDir(context: Context): File {
        /** 私有临时目录，不跨安装保留，不参与备份。 */
        val dir = File(context.filesDir, TEMP_ICON_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 根据 eventId 构造临时图标文件。
     *
     * @param dir Provider 图标临时目录。
     * @param eventId 当前事件 ID。
     */
    private fun iconFile(dir: File, eventId: String): File {
        return File(dir, eventId + ICON_FILE_SUFFIX)
    }

    /**
     * 根据 eventId 构造 `content write` 使用的半文件路径。
     *
     * @param dir Provider 图标临时目录。
     * @param eventId 当前事件 ID。
     */
    private fun tempIconFile(dir: File, eventId: String): File {
        return File(dir, eventId + ICON_TEMP_SUFFIX)
    }

    /**
     * 判断目录项是否属于 Provider 图标传输临时文件。
     *
     * @param file Provider 图标目录中的单个文件。
     */
    private fun isBridgeIconTempFile(file: File): Boolean {
        return file.name.endsWith(ICON_FILE_SUFFIX) || file.name.endsWith(ICON_TEMP_SUFFIX)
    }

    /**
     * 将半文件转成待校验 PNG 文件。
     *
     * @param tempFile `content write` 写入的半文件。
     * @param iconFile commit_icon 解码和 hash 校验使用的 PNG 文件。
     */
    private fun renameTempToIcon(tempFile: File, iconFile: File): Boolean {
        if (iconFile.exists() && !iconFile.delete()) {
            return false
        }
        return tempFile.renameTo(iconFile) || runCatching {
            tempFile.copyTo(iconFile, overwrite = true)
            if (!tempFile.delete()) {
                throw IOException("delete temp icon failed: ${tempFile.absolutePath}")
            }
            true
        }.getOrDefault(false)
    }
}

/**
 * Provider 图标解析结果。
 *
 * bitmap 只在本次成功传入新图标时存在，调用方可用它提取主色；复用旧图标时不重新计算主色。
 */
data class ClipboardBridgeIconResolution(
    /** 正式来源图标路径；为空表示使用占位/空图标。 */
    val iconPath: String?,
    /** 图标主色；当前由协调器在需要时填充。 */
    val iconColor: Int?,
    /** 可安全写入数据库的 iconHash；占位图路径必须为空，避免阻止后续补图。 */
    val iconHash: String?,
    /** 新传入并校验成功的图标 Bitmap，可用于提取主色。 */
    val bitmap: Bitmap?,
    /** 图标处理状态，用于 Provider 返回和日志诊断。 */
    val status: String,
) {
    companion object {
        /** 构造占位/空图标结果，不携带失败的 iconHash。 */
        fun placeholder(): ClipboardBridgeIconResolution {
            return ClipboardBridgeIconResolution(
                iconPath = null,
                iconColor = null,
                iconHash = null,
                bitmap = null,
                status = ClipboardBridgeContract.ICON_STATUS_PLACEHOLDER
            )
        }
    }
}
