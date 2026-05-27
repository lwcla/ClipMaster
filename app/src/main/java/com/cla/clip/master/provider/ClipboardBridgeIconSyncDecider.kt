package com.cla.clip.master.provider

import com.cla.clip.base.general.dao.SourceAppData
import com.cla.clip.shizuku.ClipboardBridgeContract
import java.io.File
import javax.inject.Inject

/**
 * Provider 图标同步规则决策器。
 *
 * 该类只负责根据当前请求 hash、数据库缓存和本地文件状态判断是否继续同步图标，
 * 不直接访问数据库、不读写文件，只输出稳定的业务决策结果供协调器复用。
 */
class ClipboardBridgeIconSyncDecider @Inject constructor() {

    /**
     * 判断当前来源图标是否需要继续同步。
     *
     * @param sourceAppData 数据库中的现有来源缓存，可为空。
     * @param requestIconHash Shizuku 侧当前事件携带的来源图标 hash，可为空。
     */
    fun decide(
        sourceAppData: SourceAppData?,
        requestIconHash: String?,
    ): ClipboardBridgeIconSyncDecision {
        /** 当前数据库缓存路径是否可用；只有路径存在且文件真实存在时才算命中缓存。 */
        val cachedIconFileExists = sourceAppData?.iconPath
            ?.takeIf { it.isNotBlank() }
            ?.let { iconPath -> File(iconPath).exists() }
            ?: false

        /** 当前数据库缓存 hash；空白时按没有缓存处理。 */
        val cachedIconHash = sourceAppData?.iconHash?.takeIf { it.isNotBlank() }

        if (requestIconHash.isNullOrBlank()) {
            return ClipboardBridgeIconSyncDecision(
                shouldSyncIcon = false,
                clearStaleCache = false,
                reasonCode = ClipboardBridgeContract.ICON_REASON_NO_ICON_AVAILABLE
            )
        }

        if (cachedIconHash.isNullOrBlank()) {
            return ClipboardBridgeIconSyncDecision(
                shouldSyncIcon = true,
                clearStaleCache = false,
                reasonCode = ClipboardBridgeContract.ICON_REASON_NO_CACHED_ICON
            )
        }

        if (cachedIconHash != requestIconHash) {
            return ClipboardBridgeIconSyncDecision(
                shouldSyncIcon = true,
                clearStaleCache = false,
                reasonCode = ClipboardBridgeContract.ICON_REASON_HASH_CHANGED
            )
        }

        if (!cachedIconFileExists) {
            return ClipboardBridgeIconSyncDecision(
                shouldSyncIcon = true,
                clearStaleCache = true,
                reasonCode = ClipboardBridgeContract.ICON_REASON_STALE_FILE_MISSING
            )
        }

        return ClipboardBridgeIconSyncDecision(
            shouldSyncIcon = false,
            clearStaleCache = false,
            reasonCode = ClipboardBridgeContract.ICON_REASON_CACHE_HIT
        )
    }
}
