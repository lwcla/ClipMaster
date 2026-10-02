package com.cla.clip.master.sourceapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.cla.clip.base.general.utils.extractUsableColor
import com.cla.clip.base.general.utils.iconBitmap
import com.cla.clip.base.general.utils.logW
import com.cla.clip.base.general.utils.toStableHash
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 图标文件发布器；API 24 使用同目录 rename，失败不更新数据库、不覆盖旧引用。 */
@Singleton
internal class SourceAppIconCachePublisher @Inject constructor(
    /** 只持有应用 Context，正式目录在应用私有 files 下。 */
    @param:ApplicationContext private val context: Context,
) {
    /** 正式与半文件只有经过一天宽限且不再被引用才清理，保护正在读旧路径的 UI。 */
    suspend fun cleanup(references: Set<String>, nowMillis: Long) {
        /** 在每项文件清理前检查生命周期取消，不把后台退出变成失败日志。 */
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        VersionedIconFilePublisher(File(context.filesDir, "app_icons")).cleanup(references, nowMillis) { coroutineContext.ensureActive() }
    }
    /** 转换、压缩和发布均由 IO 协程调用，取消不能写入来源表。 */
    suspend fun publish(packageName: String, drawable: Drawable): PublishedSourceAppIcon? {
        currentCoroutineContext().ensureActive()
        /** 有界小图标；iconBitmap 可能复用 Drawable 的位图，因此不主动 recycle。 */
        val bitmap = drawable.iconBitmap() ?: return null
        /** 与 Provider 相同的 hash 和主色约定。 */
        val hash = bitmap.toStableHash()
        /** 主色与正式图标同源，可空值继续使用主题兜底。 */
        val color = bitmap.extractUsableColor()
        currentCoroutineContext().ensureActive()
        return try {
            /** 唯一版本文件，系统包名仅作为文件身份而非可自由构造的路径。 */
            val file = VersionedIconFilePublisher(File(context.filesDir, "app_icons")).publish(packageName) { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            } ?: return null
            currentCoroutineContext().ensureActive()
            PublishedSourceAppIcon(file.absolutePath, hash, color)
        } catch (error: IOException) {
            logW("SourceAppIconPublisher") { "来源图标发布失败 reasonCode=publication_failed type=${error.javaClass.simpleName}" }
            null
        }
    }
}

/** 纯文件发布边界供 JVM 验证：写完整临时文件、同步并关闭后同目录转正。 */
internal class VersionedIconFilePublisher(
    /** 受控图标目录；不会接受调用方提供的最终文件路径。 */
    private val directory: File,
) {
    /** 编码失败返回 null；文件 IO 失败抛 IOException，调用方负责低敏诊断。 */
    fun publish(packageName: String, encode: (FileOutputStream) -> Boolean): File? {
        require(packageName.matches(Regex("[A-Za-z0-9_.]+"))) { "Invalid icon identity" }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Icon directory unavailable")
        /** UUID 转为无符号十进制唯一生成编号，不依赖时钟或每行计数器。 */
        val version = java.math.BigInteger(UUID.randomUUID().toString().replace("-", ""), 16).toString()
        /** 同目录唯一半文件与正式版本，旧正式文件始终保留。 */
        val temporary = File(directory, "${packageName}_$version.tmp")
        /** 正式版本不覆盖旧路径，保存后由条件 UPDATE 选择引用。 */
        val target = File(directory, "${packageName}_$version.png")
        try {
            /** 编码完成后同步文件描述符；不把未关闭输出流交给 UI。 */
            val encoded = FileOutputStream(temporary).use { stream ->
                /** 编码器的明确成功值，false 不发布任何正式文件。 */
                val success = encode(stream)
                if (success) stream.fd.sync()
                success
            }
            if (!encoded || temporary.length() == 0L) return null
            if (!temporary.renameTo(target)) throw IOException("Icon publication failed")
            return target
        } finally {
            // 编码失败或 rename 失败都移除本项半文件，不删除正式引用。
            temporary.delete()
        }
    }

    /** 仅清理本发布器命名的旧版本/半文件；既有稳定文件和任何被引用路径都保留。 */
    fun cleanup(references: Set<String>, nowMillis: Long, checkActive: () -> Unit = {}): Int {
        /** 本轮扫描一次受控目录，宽限期内未提交文件也不会被删除。 */
        val files = directory.listFiles() ?: return 0
        /** 成功删除的数量供测试和低敏诊断使用。 */
        var removed = 0
        files.forEach { file ->
            checkActive()
            if (file.isFile && file.name.matches(Regex("[A-Za-z0-9_.]+_[0-9]+\\.(png|tmp)")) &&
                file.absolutePath !in references && nowMillis - file.lastModified() > 24 * 60 * 60 * 1000L) {
                if (!file.delete()) throw IOException("Icon cleanup failed")
                removed++
            }
        }
        return removed
    }
}
