package com.cla.clip.master.logging

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** 只创建本次会话的公共文本日志，不读取、更新或删除用户旧文件。 */
internal class DownloadLogStore(
    /** 使用应用上下文，后台线程不会持有页面。 */
    private val context: Context,
    /** 每个进程本轮唯一身份，重启/重装不覆盖历史。 */
    private val session: String,
) {
    /** 返回新卷输出流；Android 存储失败抛 IO/权限异常，由录制器隔离。 */
    fun open(index: Int): OutputStream {
        /** 名称与卷号来自纯规则，不能传入任意路径。 */
        val name = AppLogRecordingPolicy.fileName(session, index)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) openMediaStore(name)
        else openLegacy(name)
    }

    /** Android 10+ 创建自己的 Download 条目，无需额外存储权限。 */
    private fun openMediaStore(name: String): OutputStream {
        /** 固定元数据，仅发布开发日志，不进入相册集合。 */
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, AppLogRecordingPolicy.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        /** 仅本次 insert 返回的 URI 可在打开失败时清理。 */
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("log_download_create_failed")
        /** 打开后发布，正在记录的文件也可从系统文件管理器读取。 */
        var output: OutputStream? = null
        try {
            output = context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("log_download_open_failed")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            check(context.contentResolver.update(uri, values, null, null) == 1)
            return output
        } catch (failure: Exception) {
            // 只释放刚创建但未就绪的卷；清理异常不能掩盖原始存储失败。
            runCatching { output?.close() }
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw failure
        }
    }

    /** 旧系统沿用已有存储授权，不从后台申请权限或保活。 */
    @Suppress("DEPRECATION")
    private fun openLegacy(name: String): OutputStream {
        check(context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
        /** 固定公共 Download 子目录，权限不足时停止日志保存。 */
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ClipMaster/logs")
        check(directory.isDirectory || directory.mkdirs())
        /** 新文件必须确实创建成功，不能覆盖同名用户文件。 */
        val target = File(directory, name)
        check(target.createNewFile())
        return FileOutputStream(target)
    }
}
