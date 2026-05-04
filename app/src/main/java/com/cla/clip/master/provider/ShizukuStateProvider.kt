package com.cla.clip.master.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.cla.clip.base.general.config.AppSetting

class ShizukuStateProvider : ContentProvider() {

    companion object {
        const val PATH_CURRENT_SUFFIX = "current_suffix"
        const val COLUMN_CURRENT_SUFFIX = "current_suffix"
    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        if (uri.lastPathSegment != PATH_CURRENT_SUFFIX) {
            return null
        }

        // 这是 Shizuku UserService 读取“当前有效 suffix”的正式通道。
        //
        // 背景：
        // Shizuku UserService 的进程名虽然属于本应用，但实际运行 UID 通常是 shell/root。
        // 它直接读取 App 私有目录里的 MMKV 不稳定，可能因为权限或 SELinux 限制读不到数据。
        // Provider 运行在 App 侧，可以正常读取 App 私有 MMKV，然后通过 Android
        // Binder/Provider 机制把结果返回给 Shizuku。
        //
        // 安全边界：
        // 这里只暴露 current suffix。suffix 只是 Shizuku UserService 进程名的一部分，
        // 用于判断旧 Shizuku 进程是否应该退出，不包含剪贴板内容、用户文本或其它敏感数据。
        // 写入仍然只发生在 App 进程，Shizuku 进程只能读取，避免旧进程把 current suffix 覆盖回旧值。
        val suffix = AppSetting.shizukuSuffix
        return MatrixCursor(arrayOf(COLUMN_CURRENT_SUFFIX)).apply {
            addRow(arrayOf(suffix))
        }
    }

    override fun getType(uri: Uri): String? {
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        return 0
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        return 0
    }
}
