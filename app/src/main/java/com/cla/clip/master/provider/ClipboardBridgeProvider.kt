package com.cla.clip.master.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logW
import com.cla.clip.shizuku.ClipboardBridgeContract
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException

/**
 * Shizuku 命令行访问的剪贴板桥接 Provider。
 *
 * 该 Provider 只用于验证主进程冷启动后能否通过悬浮窗读取剪贴板；旧 AIDL callback 通道仍保留为随时可回退路径。
 */
class ClipboardBridgeProvider : ContentProvider() {
    companion object {
        /** Provider 日志标签，用于定位 shell/root 调用和返回码。 */
        private const val TAG = "ClipboardBridgeProvider"
    }

    /** Hilt EntryPoint，延迟到首次调用时获取，避免 Provider 创建阶段过早初始化依赖。 */
    private val entryPoint: ClipboardBridgeEntryPoint by lazy {
        EntryPointAccessors.fromApplication(
            requireNotNull(context).applicationContext,
            ClipboardBridgeEntryPoint::class.java
        )
    }

    /** Provider 图标临时文件管理器。 */
    private val iconStore: ClipboardBridgeIconStore by lazy { entryPoint.clipboardBridgeIconStore() }

    /** Provider 剪贴板读取协调器。 */
    private val readCoordinator: ClipboardBridgeReadCoordinator by lazy { entryPoint.clipboardBridgeReadCoordinator() }

    /** Provider 图标提交协调器。 */
    private val iconCommitter: ClipboardBridgeIconCommitter by lazy { entryPoint.clipboardBridgeIconCommitter() }

    /**
     * Provider 创建入口。
     *
     * 当前 Provider 不需要预初始化资源，真实依赖在首次调用时懒加载。
     */
    override fun onCreate(): Boolean {
        return true
    }

    /**
     * Provider 命令调用入口。
     *
     * @param method 支持 read_clip 和 commit_icon。
     * @param arg 当前未使用，保留给 Android ContentProvider call 签名。
     * @param extras `content call` 传入的小字段。
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        /** Binder 调用方 UID；导出 Provider 必须在入口处校验。 */
        val callingUid = Binder.getCallingUid()
        if (!ClipboardBridgeCallerPolicy.isAllowed(callingUid)) {
            logW(TAG) { "Provider 拒绝调用 callingUid=$callingUid method=$method" }
            return ClipboardBridgeResult.of(ClipboardBridgeContract.CODE_INVALID_CALLER).toBundle()
        }

        /** 解析后的 Provider 请求参数；无效时不读取剪贴板。 */
        val request = ClipboardBridgeRequest.fromExtras(extras)
            ?: return ClipboardBridgeResult.of(ClipboardBridgeContract.CODE_INVALID_ARGS).toBundle()

        iconStore.cleanupExpired(requireNotNull(context).applicationContext)
        return when (method) {
            ClipboardBridgeContract.METHOD_READ_CLIP -> {
                logD(TAG) {
                    "Provider 收到 read_clip eventId=${request.eventId} packageName=${request.packageName} " +
                        "appName=${request.appName} hasIconHash=${!request.iconHash.isNullOrBlank()}"
                }
                runBlocking {
                    readCoordinator.readAndSave(request).toBundle()
                }
            }
            ClipboardBridgeContract.METHOD_COMMIT_ICON -> {
                logD(TAG) {
                    "Provider 收到 commit_icon eventId=${request.eventId} packageName=${request.packageName} " +
                        "appName=${request.appName} hasIconHash=${!request.iconHash.isNullOrBlank()}"
                }
                runBlocking {
                    iconCommitter.commit(request).toBundle()
                }
            }
            else -> {
                logW(TAG) { "Provider 不支持 method=$method callingUid=$callingUid" }
                ClipboardBridgeResult.of(ClipboardBridgeContract.CODE_INVALID_ARGS).toBundle()
            }
        }
    }

    /**
     * Provider 图标写入入口。
     *
     * `content write` 会调用该方法并把 stdin 字节写入返回的文件描述符。
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        /** Binder 调用方 UID；图标写入同样只允许 shell/root。 */
        val callingUid = Binder.getCallingUid()
        if (!ClipboardBridgeCallerPolicy.isAllowed(callingUid)) {
            logW(TAG) { "Provider 拒绝图标写入 callingUid=$callingUid uri=$uri" }
            throw FileNotFoundException("invalid caller")
        }

        if (!mode.contains("w")) {
            throw FileNotFoundException("unsupported mode: $mode")
        }

        /** 从 URI 路径中解析出的事件 ID。 */
        val eventId = iconStore.parseEventId(uri.pathSegments)
        logD(TAG) { "Provider 打开图标写入 eventId=$eventId" }
        return iconStore.openIconForWrite(requireNotNull(context).applicationContext, eventId)
    }

    /** 当前 Provider 不提供查询能力。 */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        return null
    }

    /** 当前 Provider 不提供 MIME 类型查询。 */
    override fun getType(uri: Uri): String? {
        return null
    }

    /** 当前 Provider 不提供 insert 能力。 */
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        logE(TAG) { "Provider 不支持 insert uri=$uri" }
        return null
    }

    /** 当前 Provider 不提供 delete 能力。 */
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        logE(TAG) { "Provider 不支持 delete uri=$uri" }
        return 0
    }

    /** 当前 Provider 不提供 update 能力。 */
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        logE(TAG) { "Provider 不支持 update uri=$uri" }
        return 0
    }
}
