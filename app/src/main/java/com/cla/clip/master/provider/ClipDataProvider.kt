package com.cla.clip.master.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.cla.clip.base.general.dao.ClipDao
import com.cla.clip.base.general.logE
import com.cla.clip.master.service.ClipboardService
import com.cla.clip.master.utils.ContentProviderEntryPoint
import dagger.hilt.android.EntryPointAccessors

/**
 * 提供给shizuku中的service写入剪贴板数据的ContentProvider。
 */
class ClipDataProvider : ContentProvider() {

    companion object {
        private const val TAG = "ClipDataProvider"
    }

    @Volatile
    private var cachedClipDao: ClipDao? = null

    private val clipDao: ClipDao?
        get() {
            cachedClipDao?.let { return it }

            val appCtx = runCatching { context?.applicationContext }.getOrNull() ?: return null

            return runCatching {
                EntryPointAccessors.fromApplication(
                    appCtx,
                    ContentProviderEntryPoint::class.java
                )
            }.getOrNull()?.clipDao().also {
                cachedClipDao = it
            }
        }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        return 0
    }

    override fun getType(uri: Uri): String? {
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (values == null) return null
        val dao = clipDao ?: return null

        // 读取三个数据 packageName、appName、iconBitmap
        val packageName = values.getAsString("packageName") ?: "unknown"
        val appName = values.getAsString("appName") ?: "unknown"
        val iconBitmap = values.getAsByteArray("iconBitmap")

        val ctx = context
        if (ctx == null) {
            logE(TAG) { "insert: context is null, cannot insert clip data" }
            return null
        }

        ClipboardService.start(ctx, packageName, appName, iconBitmap)
        return null
    }

    override fun onCreate(): Boolean {
        // onCreate\(\) 的返回值用于告诉系统：这个 ContentProvider 是否初始化成功。
        // 返回 true：初始化成功，Provider 可正常对外提供服务。-
        // 返回 false：初始化失败，Provider 不可用，后续通过该 Provider 的访问会失败。
        return true
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        return null
    }

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        return 0
    }
}