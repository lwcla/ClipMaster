package com.cla.clip.shizuku

import android.graphics.Bitmap
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logW
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Provider 图标预判与补全协调器。
 *
 * 图标链路独立于剪贴 payload：预判、写入或提交失败都只记录日志，不能取消或阻塞剪贴正文入库。
 *
 * @param serviceScope Shizuku 服务进程内协程作用域，用于启动独立图标任务。
 * @param providerCommandClient Provider 命令客户端。
 */
internal class ClipboardBridgeIconSyncCoordinator(
    private val serviceScope: CoroutineScope,
    private val providerCommandClient: ClipboardBridgeProviderCommandClient,
) {

    /** 正在执行的图标同步 key 集合，避免同一来源图标重复上传。 */
    private val inflightIconSyncKeys = ConcurrentHashMap.newKeySet<String>()

    /**
     * 启动 Provider 图标独立预判任务。
     *
     * @param eventId 当前剪贴事件 ID。
     * @param clipPackageName 来源应用包名。
     * @param appName 来源应用名称。
     * @param bitmap Shizuku 侧解析出的来源图标。
     * @param iconHash 来源图标 Bitmap.toStableHash()。
     */
    fun launch(
        eventId: String,
        clipPackageName: String?,
        appName: String?,
        bitmap: Bitmap?,
        iconHash: String?,
    ) {
        if (clipPackageName.isNullOrBlank() || bitmap == null || iconHash.isNullOrBlank()) {
            logD(ClipboardShizukuService.TAG) {
                "Provider 图标预判跳过 eventId=$eventId packageName=$clipPackageName reasonCode=missing_icon_args"
            }
            return
        }

        /** 同一来源图标的内存去重 key，避免多个 AppOps 回调重复上传同一份图标。 */
        val iconSyncKey = buildIconSyncKey(clipPackageName, iconHash)
        if (!inflightIconSyncKeys.add(iconSyncKey)) {
            logD(ClipboardShizukuService.TAG) { "Provider 图标预判跳过重复任务 eventId=$eventId iconSyncKey=$iconSyncKey" }
            return
        }

        serviceScope.launch {
            try {
                runProviderIconQuery(eventId, clipPackageName, appName, bitmap, iconHash, iconSyncKey)
            } finally {
                inflightIconSyncKeys.remove(iconSyncKey)
            }
        }
    }

    /**
     * 执行 Provider 图标独立预判与补图链路。
     *
     * @param eventId 当前剪贴事件 ID。
     * @param clipPackageName 来源应用包名。
     * @param appName 来源应用名称。
     * @param bitmap Shizuku 侧解析出的来源图标。
     * @param iconHash 来源图标 Bitmap.toStableHash()。
     * @param iconSyncKey 来源图标同步去重 key。
     */
    private fun runProviderIconQuery(
        eventId: String,
        clipPackageName: String,
        appName: String?,
        bitmap: Bitmap,
        iconHash: String,
        iconSyncKey: String,
    ) {
        /** query_icon_state 命令结果；只在 Provider 明确要求同步时才继续上传 PNG。 */
        val queryResult = providerCommandClient.queryIconState(eventId, clipPackageName, appName, iconHash)
        /** 图标预判是否成功返回完整决策。 */
        val queryOk = ClipboardBridgeCommandResultParser.isQueryIconStateSuccessful(queryResult.exitCode, queryResult.output)
        if (!queryOk) {
            logW(ClipboardShizukuService.TAG) {
                "Provider 图标预判失败 eventId=$eventId iconSyncKey=$iconSyncKey exit=${queryResult.exitCode} " +
                    "resultCode=${ClipboardBridgeCommandResultParser.parseResultCode(queryResult.output)} " +
                    "reasonCode=${ClipboardBridgeCommandResultParser.parseIconDecisionReason(queryResult.output)}"
            }
            return
        }

        /** Provider 返回的图标同步布尔决策。 */
        val shouldSyncIcon = ClipboardBridgeCommandResultParser.parseShouldSyncIcon(queryResult.output)
        /** Provider 返回的图标同步原因。 */
        val reasonCode = ClipboardBridgeCommandResultParser.parseIconDecisionReason(queryResult.output)
        if (shouldSyncIcon != true) {
            logD(ClipboardShizukuService.TAG) {
                "Provider 图标预判命中无需同步 eventId=$eventId iconSyncKey=$iconSyncKey reasonCode=$reasonCode"
            }
            return
        }

        /** content write 写图标是否成功；失败时等待下一次事件自然重试。 */
        val iconWriteOk = providerCommandClient.writeIcon(eventId, bitmap)
        if (!iconWriteOk) {
            logW(ClipboardShizukuService.TAG) {
                "Provider 图标写入失败 eventId=$eventId iconSyncKey=$iconSyncKey reasonCode=$reasonCode"
            }
            return
        }

        /** Provider commit_icon 命令结果；只有 ok + saved/reused 才算图标补齐成功。 */
        val commitResult = providerCommandClient.commitIcon(eventId, clipPackageName, appName, iconHash)
        /** 图标提交是否按既有缓存语义完成。 */
        val commitOk = ClipboardBridgeCommandResultParser.isCommitIconSuccessful(commitResult.exitCode, commitResult.output)
        if (commitOk) {
            logD(ClipboardShizukuService.TAG) { "Provider 图标补全成功 eventId=$eventId iconSyncKey=$iconSyncKey iconHash=$iconHash" }
        } else {
            logW(ClipboardShizukuService.TAG) {
                "Provider 图标补全失败 eventId=$eventId iconSyncKey=$iconSyncKey exit=${commitResult.exitCode} " +
                    "resultCode=${ClipboardBridgeCommandResultParser.parseResultCode(commitResult.output)} " +
                    "iconStatus=${ClipboardBridgeCommandResultParser.parseIconStatus(commitResult.output)}"
            }
        }
    }

    /**
     * 构造来源图标同步的内存去重 key。
     *
     * @param packageName 来源应用包名。
     * @param iconHash 来源图标 hash。
     */
    internal fun buildIconSyncKey(packageName: String, iconHash: String): String {
        return "$packageName#$iconHash"
    }
}
