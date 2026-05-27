package com.cla.clip.shizuku

/**
 * Shizuku 进程与主进程 ContentProvider 通道共享的轻量协议。
 *
 * 该协议只承载命令行可稳定传递的小字段；图标二进制通过 `content write` 的 stdin 传输，避免命令参数过长或转义失败。
 */
object ClipboardBridgeContract {
    /** Provider authority 后缀；完整 authority 由 applicationId + suffix 组成。 */
    const val AUTHORITY_SUFFIX = ".clipboard-bridge"

    /** Provider `call` 方法名：读取剪贴板并保存一条剪贴记录。 */
    const val METHOD_READ_CLIP = "read_clip"

    /** Provider `call` 方法名：判断来源图标是否需要同步。 */
    const val METHOD_QUERY_ICON_STATE = "query_icon_state"

    /** Provider `call` 方法名：提交已写入的图标并更新来源 App 图标缓存。 */
    const val METHOD_COMMIT_ICON = "commit_icon"

    /** Provider 图标写入路径首段：`content://authority/icon/<eventId>`。 */
    const val PATH_ICON = "icon"

    /** `content call` extra：一次剪贴事件的短追踪 ID。 */
    const val EXTRA_EVENT_ID = "eventId"

    /** `content call` extra：写入剪贴板的来源应用包名，可为空表示未知来源。 */
    const val EXTRA_PACKAGE_NAME = "packageName"

    /** `content call` extra：写入剪贴板的来源应用名称，可为空表示未知名称。 */
    const val EXTRA_APP_NAME = "appName"

    /** `content call` extra：Shizuku 侧计算出的来源图标稳定哈希，可为空表示没有图标。 */
    const val EXTRA_ICON_HASH = "iconHash"

    /** Provider 返回字段：结构化结果码，供 Shizuku 侧判断本次 Provider 通道是否真实成功。 */
    const val RESULT_CODE = "resultCode"

    /** Provider 返回字段：是否已经完成读取和入库；不能只依赖 content 命令 exitCode。 */
    const val RESULT_SAVED = "saved"

    /** Provider 返回字段：是否成功读取到剪贴板内容。 */
    const val RESULT_READ_CLIP = "readClip"

    /** Provider 返回字段：是否成功添加过悬浮窗。 */
    const val RESULT_OVERLAY_ADDED = "overlayAdded"

    /** Provider 返回字段：图标传输是否被使用，缺失或失败时用于诊断降级原因。 */
    const val RESULT_ICON_STATUS = "iconStatus"

    /** Provider 返回字段：本次来源图标是否需要继续同步。 */
    const val RESULT_SHOULD_SYNC_ICON = "shouldSyncIcon"

    /** Provider 返回字段：本次图标同步决策原因。 */
    const val RESULT_ICON_DECISION_REASON = "iconDecisionReason"

    /** Provider 结果码：读取剪贴板并完成入库。 */
    const val CODE_OK = "ok"

    /** Provider 结果码：调用方不是 shell/root，拒绝读取剪贴板。 */
    const val CODE_INVALID_CALLER = "invalid_caller"

    /** Provider 结果码：缺少 eventId 或 method 不匹配等参数错误。 */
    const val CODE_INVALID_ARGS = "invalid_args"

    /** Provider 结果码：图标文件不存在、写入失败或哈希不匹配，已按占位图策略降级。 */
    const val CODE_ICON_MISSING = "icon_missing"

    /** Provider 结果码：悬浮窗添加失败，无法进入剪贴板读取阶段。 */
    const val CODE_OVERLAY_FAILED = "overlay_failed"

    /** Provider 结果码：剪贴板为空或没有可保存内容。 */
    const val CODE_NO_CLIP = "no_clip"

    /** Provider 结果码：读取剪贴板或入库过程失败。 */
    const val CODE_READ_FAILED = "read_failed"

    /** Provider 结果码：Provider 等待入库完成超时，后台任务可能仍在继续。 */
    const val CODE_TIMEOUT = "timeout"

    /** 图标状态：本次传输的 PNG 已校验并保存。 */
    const val ICON_STATUS_SAVED = "saved"

    /** 图标状态：复用了数据库中同一 hash 的旧图标。 */
    const val ICON_STATUS_REUSED = "reused"

    /** 图标状态：本次没有可用图标，使用占位/空图标继续。 */
    const val ICON_STATUS_PLACEHOLDER = "placeholder"

    /** 图标决策：数据库图标 hash 命中且文件仍存在。 */
    const val ICON_REASON_CACHE_HIT = "cache_hit"

    /** 图标决策：数据库图标 hash 命中但本地文件缺失。 */
    const val ICON_REASON_STALE_FILE_MISSING = "stale_file_missing"

    /** 图标决策：数据库没有任何来源图标缓存。 */
    const val ICON_REASON_NO_CACHED_ICON = "no_cached_icon"

    /** 图标决策：数据库图标 hash 与当前来源图标 hash 不同。 */
    const val ICON_REASON_HASH_CHANGED = "hash_changed"

    /** 图标决策：当前事件没有可用图标参数，不需要继续同步。 */
    const val ICON_REASON_NO_ICON_AVAILABLE = "no_icon_available"

    /**
     * 拼出 Provider authority。
     *
     * @param applicationId 当前宿主应用 id，release/debug 都必须和 Manifest 中的 `${applicationId}` 保持一致。
     */
    fun authority(applicationId: String): String {
        return applicationId + AUTHORITY_SUFFIX
    }

    /**
     * 拼出图标写入 URI。
     *
     * @param applicationId 当前宿主应用 id。
     * @param eventId 当前剪贴事件 ID，只允许由调用方传入文件名安全的短 ID。
     */
    fun iconUri(applicationId: String, eventId: String): String {
        return "content://${authority(applicationId)}/$PATH_ICON/$eventId"
    }

    /**
     * 拼出 Provider call URI。
     *
     * @param applicationId 当前宿主应用 id。
     */
    fun callUri(applicationId: String): String {
        return "content://${authority(applicationId)}"
    }
}

/**
 * 解析 `content call` 命令输出。
 *
 * Android `content` 命令即使成功调用 Provider，也只会把 Bundle 打印成文本；这里用低耦合文本解析确认 Provider 明确返回 `saved=true` 和 `resultCode=ok`。
 */
object ClipboardBridgeCommandResultParser {
    /** Provider resultCode 的文本正则，兼容 Bundle 字段顺序变化。 */
    private val resultCodeRegex = Regex("""resultCode=([^,\]\}\s]+)""")

    /** Provider saved 字段的文本正则，兼容 Bundle 字段顺序变化。 */
    private val savedRegex = Regex("""saved=([^,\]\}\s]+)""")

    /** Provider iconStatus 字段的文本正则，兼容 Bundle 字段顺序变化。 */
    private val iconStatusRegex = Regex("""iconStatus=([^,\]\}\s]+)""")

    /** Provider shouldSyncIcon 字段的文本正则，兼容 Bundle 字段顺序变化。 */
    private val shouldSyncIconRegex = Regex("""shouldSyncIcon=([^,\]\}\s]+)""")

    /** Provider iconDecisionReason 字段的文本正则，兼容 Bundle 字段顺序变化。 */
    private val iconDecisionReasonRegex = Regex("""iconDecisionReason=([^,\]\}\s]+)""")

    /**
     * 判断 Provider 命令是否真实完成读取入库。
     *
     * @param exitCode `content call` 进程退出码。
     * @param output `content call` 标准输出和错误输出合并后的文本。
     */
    fun isSuccessful(exitCode: Int, output: String): Boolean {
        /** 命令层必须成功，否则 Provider 可能根本没有被调用。 */
        val commandSucceeded = exitCode == 0 && !output.contains("Error", ignoreCase = true)
        if (!commandSucceeded) {
            return false
        }

        /** Provider 必须明确返回 ok，不能把任意 Bundle 输出都当成功。 */
        val resultCode = parseResultCode(output)

        /** Provider 必须明确返回 saved=true，表示本次读取和入库已经完成。 */
        val saved = parseSaved(output)
        return resultCode == ClipboardBridgeContract.CODE_OK && saved == true
    }

    /**
     * 判断 `read_clip` 是否真实完成剪贴读取和入库。
     *
     * @param exitCode `content call` 进程退出码。
     * @param output `content call` 标准输出和错误输出合并后的文本。
     */
    fun isReadClipSuccessful(exitCode: Int, output: String): Boolean {
        return isSuccessful(exitCode, output)
    }

    /**
     * 判断 `query_icon_state` 是否成功返回图标同步决策。
     *
     * @param exitCode `content call` 进程退出码。
     * @param output `content call` 标准输出和错误输出合并后的文本。
     */
    fun isQueryIconStateSuccessful(exitCode: Int, output: String): Boolean {
        /** 命令层必须成功，否则 Provider 可能根本没有被调用。 */
        val commandSucceeded = exitCode == 0 && !output.contains("Error", ignoreCase = true)
        if (!commandSucceeded) {
            return false
        }

        /** Provider 必须明确返回 ok，才能把后续 shouldSyncIcon 当作有效决策。 */
        return parseResultCode(output) == ClipboardBridgeContract.CODE_OK &&
            parseShouldSyncIcon(output) != null &&
            !parseIconDecisionReason(output).isNullOrBlank()
    }

    /**
     * 判断 `commit_icon` 是否真实完成来源图标补齐。
     *
     * @param exitCode `content call` 进程退出码。
     * @param output `content call` 标准输出和错误输出合并后的文本。
     */
    fun isCommitIconSuccessful(exitCode: Int, output: String): Boolean {
        /** 命令层必须成功，否则 Provider 可能根本没有被调用。 */
        val commandSucceeded = exitCode == 0 && !output.contains("Error", ignoreCase = true)
        if (!commandSucceeded) {
            return false
        }

        /** Provider 必须明确返回 ok，不能把任意 Bundle 输出都当图标补齐成功。 */
        val resultCode = parseResultCode(output)

        /** Provider 必须明确返回 saved 或 reused，表示图标缓存已可复用。 */
        val iconStatus = parseIconStatus(output)
        return resultCode == ClipboardBridgeContract.CODE_OK &&
            (iconStatus == ClipboardBridgeContract.ICON_STATUS_SAVED || iconStatus == ClipboardBridgeContract.ICON_STATUS_REUSED)
    }

    /**
     * 从 `content call` 输出中提取 Provider resultCode。
     *
     * @param output `content call` 打印出的 Bundle 文本。
     */
    fun parseResultCode(output: String): String? {
        return resultCodeRegex.find(output)?.groupValues?.getOrNull(1)
    }

    /**
     * 从 `content call` 输出中提取 saved 布尔值。
     *
     * @param output `content call` 打印出的 Bundle 文本。
     */
    fun parseSaved(output: String): Boolean? {
        return savedRegex.find(output)?.groupValues?.getOrNull(1)?.toBooleanStrictOrNull()
    }

    /**
     * 从 `content call` 输出中提取 Provider iconStatus。
     *
     * @param output `content call` 打印出的 Bundle 文本。
     */
    fun parseIconStatus(output: String): String? {
        return iconStatusRegex.find(output)?.groupValues?.getOrNull(1)
    }

    /**
     * 从 `content call` 输出中提取 shouldSyncIcon 布尔值。
     *
     * @param output `content call` 打印出的 Bundle 文本。
     */
    fun parseShouldSyncIcon(output: String): Boolean? {
        return shouldSyncIconRegex.find(output)?.groupValues?.getOrNull(1)?.toBooleanStrictOrNull()
    }

    /**
     * 从 `content call` 输出中提取图标同步决策原因。
     *
     * @param output `content call` 打印出的 Bundle 文本。
     */
    fun parseIconDecisionReason(output: String): String? {
        return iconDecisionReasonRegex.find(output)?.groupValues?.getOrNull(1)
    }
}
