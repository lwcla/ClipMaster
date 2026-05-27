package com.cla.clip.master.provider

import android.content.Context
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.base.general.utils.extractUsableColor
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import com.cla.clip.master.work.BackupAutoScheduler
import com.cla.clip.shizuku.ClipboardBridgeContract
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * Provider 图标异步补全协调器。
 *
 * 只负责 commit_icon 阶段的图标校验、正式保存和来源 App 图标字段更新；不读取剪贴板、不发送剪贴通知。
 */
class ClipboardBridgeIconCommitter @Inject constructor(
    /** 应用 Context，用于读取私有图标临时目录和调度自动备份。 */
    @param:ApplicationContext private val appContext: Context,
    /** Provider 图标临时传输目录管理器，用于消费 content write 写入的半文件。 */
    private val iconStore: ClipboardBridgeIconStore,
    /** 剪贴仓库，用于更新来源 App 图标缓存并触发 Room 观察刷新。 */
    private val clipRepository: Lazy<ClipRepository>,
) {
    companion object {
        /** 图标提交日志标签，用于定位 commit_icon 阶段耗时和失败原因。 */
        private const val TAG = "ClipboardBridgeIconCommitter"

        /** commit_icon 同步等待上限，避免 shell content call 长时间挂起。 */
        private const val COMMIT_TIMEOUT_MS = 3_000L
    }

    /**
     * 提交图标并更新来源 App 缓存。
     *
     * @param request Provider commit_icon 请求参数。
     */
    suspend fun commit(request: ClipboardBridgeRequest): ClipboardBridgeResult {
        return try {
            withTimeout(COMMIT_TIMEOUT_MS) {
                commitInternal(request)
            }
        } catch (exception: TimeoutCancellationException) {
            logW(TAG) { "Provider 图标提交超时 eventId=${request.eventId} packageName=${request.packageName}" }
            ClipboardBridgeResult.of(
                resultCode = ClipboardBridgeContract.CODE_TIMEOUT,
                iconStatus = ClipboardBridgeContract.ICON_STATUS_PLACEHOLDER
            )
        } catch (throwable: Throwable) {
            logE(TAG, throwable) { "Provider 图标提交失败 eventId=${request.eventId} packageName=${request.packageName}" }
            ClipboardBridgeResult.of(
                resultCode = ClipboardBridgeContract.CODE_READ_FAILED,
                iconStatus = ClipboardBridgeContract.ICON_STATUS_PLACEHOLDER
            )
        }
    }

    /**
     * 执行图标提交的核心流程。
     *
     * @param request Provider commit_icon 请求参数。
     */
    private suspend fun commitInternal(request: ClipboardBridgeRequest): ClipboardBridgeResult {
        /** 来源包名是图标缓存的唯一业务身份，缺失时不能更新数据库。 */
        val packageName = request.packageName
            ?: return ClipboardBridgeResult.of(ClipboardBridgeContract.CODE_INVALID_ARGS)

        /** Bitmap.toStableHash() 是数据库图标签名语义，缺失时不能确认缓存是否有效。 */
        val iconHash = request.iconHash
            ?: return ClipboardBridgeResult.of(ClipboardBridgeContract.CODE_INVALID_ARGS)

        /** 已有同 hash 来源 App 缓存可直接复用，避免重复 decode 和覆盖图标文件。 */
        val cachedSourceApp = clipRepository.get().loadSourceApp(packageName)
        if (cachedSourceApp?.iconHash == iconHash && !cachedSourceApp.iconPath.isNullOrBlank()) {
            logI(TAG) { "Provider 图标提交复用缓存 eventId=${request.eventId} packageName=$packageName iconHash=$iconHash" }
            return ClipboardBridgeResult.of(
                resultCode = ClipboardBridgeContract.CODE_OK,
                iconStatus = ClipboardBridgeContract.ICON_STATUS_REUSED
            )
        }

        /** 图标校验和保存结果；失败时不写入新的 iconHash，等待后续事件自然重试。 */
        val iconResolution = iconStore.commitIcon(
            context = appContext,
            request = request
        )
        if (iconResolution.status != ClipboardBridgeContract.ICON_STATUS_SAVED ||
            iconResolution.iconPath.isNullOrBlank() ||
            iconResolution.iconHash.isNullOrBlank()
        ) {
            return ClipboardBridgeResult.of(
                resultCode = ClipboardBridgeContract.CODE_ICON_MISSING,
                iconStatus = iconResolution.status
            )
        }

        /** 图标主色；保存成功才提取并写库，避免占位图污染来源 App 颜色。 */
        val primaryColor = iconResolution.bitmap?.extractUsableColor()
        clipRepository.get().updateSourceAppIcon(
            packageName = packageName,
            appName = request.appName,
            iconPath = iconResolution.iconPath,
            primaryColor = primaryColor,
            iconHash = iconResolution.iconHash
        )
        BackupAutoScheduler.markDirtyAndSchedule(appContext)

        logI(TAG) {
            "Provider 图标提交成功 eventId=${request.eventId} packageName=$packageName iconHash=${iconResolution.iconHash}"
        }
        return ClipboardBridgeResult.of(
            resultCode = ClipboardBridgeContract.CODE_OK,
            iconStatus = ClipboardBridgeContract.ICON_STATUS_SAVED
        )
    }
}
