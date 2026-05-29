package com.cla.clip.master.provider

import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.View
import android.view.WindowManager
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import com.cla.clip.master.utils.ClipHelper
import com.cla.clip.shizuku.ClipboardBridgeContract
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import java.io.File

/**
 * Provider 剪贴板读取协调器。
 *
 * 该类复用 `ClipboardService.magic()` 已验证的悬浮窗读取思路，但不启动 Service，避免 Provider 冷启动验证再次撞后台服务限制。
 */
class ClipboardBridgeReadCoordinator @Inject constructor(
    /** 应用 Context，用于访问 ClipboardManager、WindowManager 和私有文件目录。 */
    @param:ApplicationContext private val appContext: Context,
    /** 剪贴板入库助手，复用现有去重、链接解析、备份 dirty 和通知逻辑。 */
    private val clipHelper: Lazy<ClipHelper>,
    /** 剪贴仓库，用于复用旧来源图标并写入剪贴记录。 */
    private val clipRepository: Lazy<ClipRepository>,
) {
    companion object {
        /** Provider 读取协调器日志标签，用于排查冷启动和悬浮窗读取结果。 */
        private const val TAG = "ClipboardBridgeReadCoordinator"

        /** Provider 同步等待读取和入库的最大时间；超过后返回 timeout，避免 Binder 调用长期挂起。 */
        private const val READ_TIMEOUT_MS = 3_000L
    }

    /** WindowManager 用于添加 1x1 透明悬浮 View。 */
    private val windowManager by lazy { appContext.getSystemService(WindowManager::class.java) as WindowManager }

    /** ClipboardManager 用于读取系统剪贴板当前内容。 */
    private val clipboardManager by lazy { appContext.getSystemService(ClipboardManager::class.java) }

    /**
     * Provider 入口同步等待一次读取结果。
     *
     * @param request Provider 解析后的 read_clip 请求。
     */
    suspend fun readAndSave(request: ClipboardBridgeRequest): ClipboardBridgeResult {
        return try {
            withTimeout(READ_TIMEOUT_MS) {
                readAndSaveInternal(request)
            }
        } catch (exception: TimeoutCancellationException) {
            logW(TAG) { "Provider 读取超时 eventId=${request.eventId} packageName=${request.packageName}" }
            ClipboardBridgeResult.of(ClipboardBridgeContract.CODE_TIMEOUT)
        } catch (throwable: Throwable) {
            logE(TAG, throwable) { "Provider 读取失败 eventId=${request.eventId} packageName=${request.packageName}" }
            ClipboardBridgeResult.of(ClipboardBridgeContract.CODE_READ_FAILED)
        }
    }

    /**
     * 执行悬浮窗读取和入库。
     *
     * @param request Provider 解析后的 read_clip 请求。
     */
    private suspend fun readAndSaveInternal(request: ClipboardBridgeRequest): ClipboardBridgeResult {
        /** 数据库中同包名的旧来源 App 信息，用于 read_clip 快速入库时先展示旧图标。 */
        val sourceAppData = request.packageName
            ?.let { packageName -> clipRepository.get().loadSourceApp(packageName) }

        /** 旧来源图标文件是否真实存在；失效路径不能再被轻量 read_clip 回写进来源缓存。 */
        val hasReadableCachedIcon = sourceAppData?.iconPath
            ?.takeIf { it.isNotBlank() }
            ?.let { iconPath -> File(iconPath).exists() }
            ?: false

        /** Provider 入库使用的图标路径；仅当旧缓存路径真实存在时才复用，避免坏路径被重新写回。 */
        val iconPath = sourceAppData?.iconPath?.takeIf { hasReadableCachedIcon }

        /** Provider 入库使用的图标主色；只和可读旧图标一起复用。 */
        val iconColor = sourceAppData?.primaryColor?.takeIf { hasReadableCachedIcon }

        /** Provider 入库使用的图标 hash；只有可读旧图标可用时才写入，避免坏缓存语义重新落库。 */
        val cachedIconHash = sourceAppData?.iconHash?.takeIf { hasReadableCachedIcon }

        /** Provider 读取剪贴板的可见窗口结果，必须先添加窗口再读剪贴板。 */
        val windowResult = addOverlayAndReadClip()
        if (!windowResult.overlayAdded) {
            return ClipboardBridgeResult.of(
                resultCode = ClipboardBridgeContract.CODE_OVERLAY_FAILED,
                overlayAdded = false,
                iconStatus = ClipboardBridgeContract.ICON_STATUS_PLACEHOLDER
            )
        }

        /** 剪贴板 item 为空时返回 no_clip，不制造空记录。 */
        val clipItem = windowResult.clipItem
            ?: return ClipboardBridgeResult.of(
                resultCode = ClipboardBridgeContract.CODE_NO_CLIP,
                readClip = false,
                overlayAdded = true,
                iconStatus = ClipboardBridgeContract.ICON_STATUS_PLACEHOLDER
            )

        clipHelper.get().processClip(
            item = clipItem,
            packageName = request.packageName,
            appName = request.appName,
            iconPath = iconPath,
            iconColor = iconColor,
            iconHash = cachedIconHash
        )

        logI(TAG) {
            "Provider 读取入库成功 eventId=${request.eventId} packageName=${request.packageName} " +
                "appName=${request.appName} hasCachedIcon=${!iconPath.isNullOrBlank()}"
        }
        return ClipboardBridgeResult.of(
            resultCode = ClipboardBridgeContract.CODE_OK,
            saved = true,
            readClip = true,
            overlayAdded = true,
            iconStatus = if (!iconPath.isNullOrBlank()) {
                ClipboardBridgeContract.ICON_STATUS_REUSED
            } else {
                ClipboardBridgeContract.ICON_STATUS_PLACEHOLDER
            }
        )
    }

    /**
     * 在主线程添加透明悬浮窗、读取剪贴板并立即移除。
     *
     * WindowManager 要求 UI 相关操作在主线程执行；剪贴板内容本身不写入日志，避免泄露用户数据。
     */
    private suspend fun addOverlayAndReadClip(): OverlayReadResult {
        return withContext(Dispatchers.Main.immediate) {
            /** 本次添加的透明 View，读取完成或失败后必须清理。 */
            var view: View? = null
            runCatching {
                view = View(appContext)
                windowManager.addView(view, buildOverlayLayoutParams())

                /** 当前剪贴板 ClipData 的首个 item；读取失败按空剪贴板处理。 */
                val clipItem = runCatching { clipboardManager.primaryClip?.getItemAt(0) }.getOrElse {
                    logE(TAG, it) { "Provider 读取剪贴板失败，按空剪贴板处理" }
                    null
                }
                OverlayReadResult(overlayAdded = true, clipItem = clipItem)
            }.onFailure { throwable ->
                logE(TAG, throwable) { "Provider 添加悬浮窗或读取剪贴板失败" }
            }.also {
                runCatching {
                    view?.let { addedView -> windowManager.removeView(addedView) }
                }.onFailure { throwable ->
                    logE(TAG, throwable) { "Provider 移除悬浮窗失败" }
                }
            }.getOrElse {
                OverlayReadResult(overlayAdded = false, clipItem = null)
            }
        }
    }

    /**
     * 构建 1x1 透明悬浮窗参数。
     *
     * Android O+ 使用 TYPE_APPLICATION_OVERLAY；低版本沿用现有 ClipboardService 的 TYPE_PHONE 兼容策略。
     */
    private fun buildOverlayLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            x = 0
            y = 0
            width = 1
            height = 1
        }
    }
}

/**
 * 透明悬浮窗读取剪贴板的结果。
 *
 * @param overlayAdded 是否成功添加过悬浮窗。
 * @param clipItem 剪贴板首个 item，可能为空。
 */
private data class OverlayReadResult(
    val overlayAdded: Boolean,
    val clipItem: android.content.ClipData.Item?,
)
