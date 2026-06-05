package com.cla.clip.master.provider

import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import com.cla.clip.shizuku.ClipboardBridgeContract
import dagger.Lazy
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * Provider 来源图标预判协调器。
 *
 * 该类只负责根据当前来源图标 hash 和数据库缓存判断是否需要继续同步图标，
 * 并在发现坏路径时先清空失效缓存，便于后续图标链路独立重建来源缓存。
 */
class ClipboardBridgeIconQueryCoordinator @Inject constructor(
    /** 剪贴仓库，用于读取并必要时清理来源缓存。 */
    private val clipRepository: Lazy<ClipRepository>,
    /** 图标同步规则决策器，统一缓存命中和坏路径语义。 */
    private val iconSyncDecider: ClipboardBridgeIconSyncDecider,
) {
    companion object {
        /** 图标预判日志标签，用于定位 query_icon_state 决策结果。 */
        private const val TAG = "ClipboardBridgeIconQuery"

        /** 图标预判同步等待上限，避免 shell content call 长时间挂起。 */
        private const val QUERY_TIMEOUT_MS = 1_500L
    }

    /**
     * 返回当前来源图标是否需要继续同步。
     *
     * @param request Provider query_icon_state 请求参数。
     */
    suspend fun query(request: ClipboardBridgeRequest): ClipboardBridgeResult {
        return try {
            withTimeout(QUERY_TIMEOUT_MS) {
                queryInternal(request)
            }
        } catch (exception: TimeoutCancellationException) {
            logW(TAG) { "Provider 图标预判超时 eventId=${request.eventId} packageName=${request.packageName}" }
            ClipboardBridgeResult.of(
                resultCode = ClipboardBridgeContract.CODE_TIMEOUT,
                shouldSyncIcon = false,
                iconDecisionReason = ClipboardBridgeContract.ICON_REASON_NO_ICON_AVAILABLE
            )
        } catch (throwable: Throwable) {
            logW(TAG) {
                "Provider 图标预判失败 eventId=${request.eventId} packageName=${request.packageName} type=${throwable::class.simpleName}"
            }
            ClipboardBridgeResult.of(
                resultCode = ClipboardBridgeContract.CODE_ICON_COMMIT_FAILED,
                shouldSyncIcon = false,
                iconDecisionReason = ClipboardBridgeContract.ICON_REASON_NO_ICON_AVAILABLE
            )
        }
    }

    /**
     * 执行图标预判核心逻辑。
     *
     * @param request Provider query_icon_state 请求参数。
     */
    private suspend fun queryInternal(request: ClipboardBridgeRequest): ClipboardBridgeResult {
        /** query_icon_state 以来源包名作为唯一业务身份；缺失时无法关联来源缓存。 */
        val packageName = request.packageName
            ?: return ClipboardBridgeResult.of(ClipboardBridgeContract.CODE_INVALID_ARGS)

        /** 数据库中已存在的来源 App 缓存，用于判断 hash 是否命中以及图标文件是否仍存在。 */
        val cachedSourceApp = clipRepository.get().loadSourceApp(packageName)

        /** 当前请求的图标同步决策结果。 */
        val decision = iconSyncDecider.decide(
            sourceAppData = cachedSourceApp,
            requestIconHash = request.iconHash
        )

        if (decision.clearStaleCache) {
            clipRepository.get().clearSourceAppIconCache(packageName)
        }

        logI(TAG) {
            "Provider 图标预判完成 eventId=${request.eventId} packageName=$packageName shouldSyncIcon=${decision.shouldSyncIcon} reasonCode=${decision.reasonCode}"
        }
        return ClipboardBridgeResult.of(
            resultCode = ClipboardBridgeContract.CODE_OK,
            iconStatus = if (decision.shouldSyncIcon) {
                ClipboardBridgeContract.ICON_STATUS_PLACEHOLDER
            } else {
                ClipboardBridgeContract.ICON_STATUS_REUSED
            },
            shouldSyncIcon = decision.shouldSyncIcon,
            iconDecisionReason = decision.reasonCode
        )
    }
}
