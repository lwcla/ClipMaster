package com.cla.clip.master.provider

import android.os.Bundle
import com.cla.clip.shizuku.ClipboardBridgeContract

/**
 * Provider 剪贴/图标桥接请求参数。
 *
 * 只包含命令行适合传递的小字段；图标 PNG 通过 eventId 关联到临时文件。
 */
data class ClipboardBridgeRequest(
    /** 一次剪贴事件的短追踪 ID，同时也是临时图标文件名的一部分。 */
    val eventId: String,
    /** 来源应用包名，可为空表示未知来源。 */
    val packageName: String?,
    /** 来源应用名称，可为空表示未知名称。 */
    val appName: String?,
    /** 来源图标稳定哈希，可为空表示本次没有图标。 */
    val iconHash: String?,
) {
    companion object {
        /** eventId 只允许文件名安全字符，防止通过 Provider 路径写出临时目录。 */
        private val eventIdRegex = Regex("[A-Za-z0-9._-]{1,80}")

        /**
         * 从原始字段构造请求参数。
         *
         * @param eventId 一次剪贴事件 ID；必须是文件名安全短字符串。
         * @param packageName 来源应用包名，空白时按未知来源处理。
         * @param appName 来源应用名称，空白时按未知名称处理。
         * @param iconHash 来源图标 hash，空白时表示没有可校验图标。
         */
        fun fromValues(
            eventId: String?,
            packageName: String?,
            appName: String?,
            iconHash: String?,
        ): ClipboardBridgeRequest? {
            /** 校验后的事件 ID；无效时 Provider 不能继续处理。 */
            val validEventId = eventId?.takeIf { eventIdRegex.matches(it) } ?: return null

            return ClipboardBridgeRequest(
                eventId = validEventId,
                packageName = packageName?.takeIf { it.isNotBlank() },
                appName = appName?.takeIf { it.isNotBlank() },
                iconHash = iconHash?.takeIf { it.isNotBlank() }
            )
        }

        /**
         * 从 `content call` extras 中解析请求参数。
         *
         * @param extras Android `content` 命令传入的 Bundle。
         */
        fun fromExtras(extras: Bundle?): ClipboardBridgeRequest? {
            return fromValues(
                eventId = extras?.getString(ClipboardBridgeContract.EXTRA_EVENT_ID),
                packageName = extras?.getString(ClipboardBridgeContract.EXTRA_PACKAGE_NAME),
                appName = extras?.getString(ClipboardBridgeContract.EXTRA_APP_NAME),
                iconHash = extras?.getString(ClipboardBridgeContract.EXTRA_ICON_HASH)
            )
        }
    }
}
