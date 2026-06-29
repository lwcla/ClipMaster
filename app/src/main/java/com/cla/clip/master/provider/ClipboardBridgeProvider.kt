package com.cla.clip.master.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.cla.clip.base.general.config.MmkvInitializer
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
 * 该 Provider 只接收 Shizuku 进程直读后的 payload、来源图标和身份查询，不再读取系统剪贴板。
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

    /** Provider 剪贴 payload 临时文件管理器。 */
    private val clipPayloadStore: ClipboardBridgeClipPayloadStore by lazy { entryPoint.clipboardBridgeClipPayloadStore() }

    /** Provider 剪贴 payload 提交协调器。 */
    private val clipCommitCoordinator: ClipboardBridgeClipCommitCoordinator by lazy {
        entryPoint.clipboardBridgeClipCommitCoordinator()
    }

    /** Provider 图标预判协调器。 */
    private val iconQueryCoordinator: ClipboardBridgeIconQueryCoordinator by lazy { entryPoint.clipboardBridgeIconQueryCoordinator() }

    /** Provider 图标提交协调器。 */
    private val iconCommitter: ClipboardBridgeIconCommitter by lazy { entryPoint.clipboardBridgeIconCommitter() }

    /** Provider Shizuku 进程身份查询协调器。 */
    private val shizukuProcessCoordinator: ClipboardBridgeShizukuProcessCoordinator by lazy {
        entryPoint.clipboardBridgeShizukuProcessCoordinator()
    }

    /**
     * Provider 创建入口。
     *
     * ContentProvider 冷启动早于 Application.onCreate()，这里先确认 MMKV 初始化，真实业务依赖仍在首次调用时懒加载。
     */
    override fun onCreate(): Boolean {
        /** Provider 所在的应用 Context；用于在 Application.onCreate() 之前准备 MMKV 默认实例。 */
        val appContext = requireNotNull(context).applicationContext
        MmkvInitializer.ensureInitialized(appContext, "clipboard_bridge_provider")
        return true
    }

    /**
     * Provider 命令调用入口。
     *
     * @param method 支持 commit_clip、query_icon_state、commit_icon 和 query_shizuku_process。
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

        /** 当前 method 是否属于保留的 Provider 公共接口；旧 read_clip 会在这里被拒绝。 */
        val methodSupported = method == ClipboardBridgeContract.METHOD_COMMIT_CLIP ||
            method == ClipboardBridgeContract.METHOD_QUERY_ICON_STATE ||
            method == ClipboardBridgeContract.METHOD_COMMIT_ICON ||
            method == ClipboardBridgeContract.METHOD_QUERY_SHIZUKU_PROCESS
        if (!methodSupported) {
            logW(TAG) { "Provider 不支持 method=$method callingUid=$callingUid" }
            return ClipboardBridgeResult.of(ClipboardBridgeContract.CODE_INVALID_ARGS).toBundle()
        }

        return when (method) {
            ClipboardBridgeContract.METHOD_COMMIT_CLIP -> {
                /** 解析后的剪贴/来源图标请求参数；无效时不进入桥接方法。 */
                val request = ClipboardBridgeRequest.fromExtras(extras)
                    ?: return ClipboardBridgeResult.of(ClipboardBridgeContract.CODE_INVALID_ARGS).toBundle()
                /** 应用 Context；提交剪贴 payload 前清理过期临时文件。 */
                val appContext = requireNotNull(context).applicationContext
                iconStore.cleanupExpired(appContext)
                clipPayloadStore.cleanupExpired(appContext)
                logD(TAG) {
                    "Provider 收到 commit_clip eventId=${request.eventId} packageName=${request.packageName} " +
                        "appName=${request.appName} hasIconHash=${!request.iconHash.isNullOrBlank()}"
                }
                runBlocking {
                    clipCommitCoordinator.commit(request).toBundle()
                }
            }
            ClipboardBridgeContract.METHOD_QUERY_ICON_STATE -> {
                /** 解析后的剪贴/来源图标请求参数；无效时不进入桥接方法。 */
                val request = ClipboardBridgeRequest.fromExtras(extras)
                    ?: return ClipboardBridgeResult.of(ClipboardBridgeContract.CODE_INVALID_ARGS).toBundle()
                /** 应用 Context；图标预判前清理过期图标半文件，避免旧 eventId 干扰。 */
                val appContext = requireNotNull(context).applicationContext
                iconStore.cleanupExpired(appContext)
                clipPayloadStore.cleanupExpired(appContext)
                logD(TAG) {
                    "Provider 收到 query_icon_state eventId=${request.eventId} packageName=${request.packageName} " +
                        "appName=${request.appName} hasIconHash=${!request.iconHash.isNullOrBlank()}"
                }
                runBlocking {
                    iconQueryCoordinator.query(request).toBundle()
                }
            }
            ClipboardBridgeContract.METHOD_COMMIT_ICON -> {
                /** 解析后的剪贴/来源图标请求参数；无效时不进入桥接方法。 */
                val request = ClipboardBridgeRequest.fromExtras(extras)
                    ?: return ClipboardBridgeResult.of(ClipboardBridgeContract.CODE_INVALID_ARGS).toBundle()
                /** 应用 Context；提交图标前清理过期图标半文件。 */
                val appContext = requireNotNull(context).applicationContext
                iconStore.cleanupExpired(appContext)
                clipPayloadStore.cleanupExpired(appContext)
                logD(TAG) {
                    "Provider 收到 commit_icon eventId=${request.eventId} packageName=${request.packageName} " +
                        "appName=${request.appName} hasIconHash=${!request.iconHash.isNullOrBlank()}"
                }
                runBlocking {
                    iconCommitter.commit(request).toBundle()
                }
            }
            ClipboardBridgeContract.METHOD_QUERY_SHIZUKU_PROCESS -> {
                /** 解析后的身份查询请求参数；只需要 eventId 串联日志。 */
                val request = ClipboardBridgeRequest.fromExtras(extras)
                    ?: return ClipboardBridgeResult.of(ClipboardBridgeContract.CODE_INVALID_ARGS).toBundle()
                logD(TAG) { "Provider 收到 query_shizuku_process eventId=${request.eventId}" }
                shizukuProcessCoordinator.query(request).toBundle()
            }
            else -> {
                /** 理论不可达分支；保留是为了防止未来新增 method 时忘记补齐处理分支。 */
                logW(TAG) { "Provider 不支持 method=$method callingUid=$callingUid" }
                ClipboardBridgeResult.of(ClipboardBridgeContract.CODE_INVALID_ARGS).toBundle()
            }
        }
    }

    /**
     * Provider 二进制/敏感 payload 写入入口。
     *
     * `content write` 会调用该方法并把 stdin 字节写入返回的文件描述符；图标和剪贴 payload 使用不同路径与临时目录。
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        /** Binder 调用方 UID；图标写入同样只允许 shell/root。 */
        val callingUid = Binder.getCallingUid()
        if (!ClipboardBridgeCallerPolicy.isAllowed(callingUid)) {
            logW(TAG) { "Provider 拒绝文件写入 callingUid=$callingUid uri=$uri" }
            throw FileNotFoundException("invalid caller")
        }

        if (!mode.contains("w")) {
            throw FileNotFoundException("unsupported mode: $mode")
        }

        /** 应用 Context；写入入口需要用它定位私有 files 目录。 */
        val appContext = requireNotNull(context).applicationContext
        /** Provider URI 的第一段路径，用于区分图标和剪贴 payload 写入。 */
        val pathType = uri.pathSegments.firstOrNull()
        return when (pathType) {
            ClipboardBridgeContract.PATH_ICON -> {
                /** 从图标 URI 路径中解析出的事件 ID。 */
                val eventId = iconStore.parseEventId(uri.pathSegments)
                logD(TAG) { "Provider 打开图标写入 eventId=$eventId" }
                iconStore.openIconForWrite(appContext, eventId)
            }
            ClipboardBridgeContract.PATH_CLIP -> {
                /** 从剪贴 payload URI 路径中解析出的事件 ID。 */
                val eventId = clipPayloadStore.parseEventId(uri.pathSegments)
                logD(TAG) { "Provider 打开剪贴 payload 写入 eventId=$eventId" }
                clipPayloadStore.openPayloadForWrite(appContext, eventId)
            }
            else -> {
                throw FileNotFoundException("unsupported path: ${uri.pathSegments}")
            }
        }
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
