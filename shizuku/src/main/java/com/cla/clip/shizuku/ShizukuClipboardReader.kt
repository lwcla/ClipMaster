package com.cla.clip.shizuku

import android.content.ClipData
import android.content.Context
import android.os.IBinder
import com.cla.clip.base.general.utils.logE
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method

/**
 * Shizuku/shell 进程内的系统剪贴板读取器。
 *
 * 该类只封装隐藏 `IClipboard` Binder 调用和 ClipData 到 payload 的映射，不负责 Provider 提交、图标同步或数据库入库。
 */
internal class ShizukuClipboardReader {
    companion object {
        /** 读取器日志标签，用于定位隐藏 API 签名和 Binder 调用失败。 */
        private const val TAG = "ShizukuClipboardReader"

        /** shell 进程读取系统剪贴板时使用的调用包名，必须与 Shizuku/shell uid 匹配。 */
        internal const val SHELL_CLIPBOARD_CALLING_PACKAGE = "com.android.shell"

        /** 默认用户 ID；当前正式链路只读取主用户剪贴板。 */
        private const val DEFAULT_CLIPBOARD_USER_ID = 0

        /** 默认设备 ID；Android 13+ `IClipboard` 签名会要求传入默认设备剪贴板。 */
        private const val DEFAULT_CLIPBOARD_DEVICE_ID = 0
    }

    /**
     * 读取系统当前主剪贴板。
     *
     * @param userId 要读取的 Android 用户 ID；失败时返回 null 并只输出脱敏错误日志。
     */
    fun readPrimaryClip(userId: Int = DEFAULT_CLIPBOARD_USER_ID): ClipData? {
        return runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/os/ServiceManager;",
                "Landroid/content/IClipboard;"
            )

            /** ServiceManager 隐藏类，用于获取系统 clipboard Binder 服务。 */
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            /** 系统 clipboard Binder；为空表示当前 ROM 没暴露该服务或反射失败。 */
            val clipboardBinder = serviceManagerClass
                .getDeclaredMethod("getService", String::class.java)
                .invoke(null, Context.CLIPBOARD_SERVICE) as? IBinder
                ?: return@runCatching null

            /** IClipboard.Stub 隐藏类，用于把 Binder 转成系统剪贴板接口代理。 */
            val clipboardStubClass = Class.forName("android.content.IClipboard\$Stub")
            /** 系统剪贴板接口代理；真实实现运行在 system_server。 */
            val clipboardService = clipboardStubClass
                .getDeclaredMethod("asInterface", IBinder::class.java)
                .invoke(null, clipboardBinder)
                ?: return@runCatching null

            /** 当前系统版本可用的 getPrimaryClip 方法；不同 Android 版本参数数量不同。 */
            val getPrimaryClipMethod = clipboardService.javaClass.methods
                .filter { method -> method.name == "getPrimaryClip" }
                .maxByOrNull { method -> method.parameterTypes.size }
                ?: return@runCatching null

            /** 按当前 getPrimaryClip 签名构造的反射参数，兼容 package/attribution/user/device 的版本差异。 */
            val getPrimaryClipArgs = buildGetPrimaryClipArgs(getPrimaryClipMethod, userId)
            getPrimaryClipMethod.invoke(clipboardService, *getPrimaryClipArgs) as? ClipData
        }.onFailure { throwable ->
            logE(TAG, throwable) { "Shizuku 进程直读系统剪贴板失败" }
        }.getOrNull()
    }

    /**
     * 将系统 ClipData 映射成 Provider payload。
     *
     * @param clipData Shizuku 进程读到的系统剪贴板快照；为空时返回 null，让上层决定是否触发调试回退。
     * @param eventId 当前剪贴事件 ID，必须和 Provider 临时文件名一致。
     * @param capturedAtMillis 捕获时间戳，用于避免并发提交改变列表顺序。
     */
    fun toPayload(
        clipData: ClipData?,
        eventId: String,
        capturedAtMillis: Long,
    ): ClipboardBridgeClipPayload? {
        if (clipData == null) {
            return null
        }

        /** 首个剪贴 item；第一版只处理单 item，后续多 item 支持再扩展协议。 */
        val firstItem = clipData.takeIf { data -> data.itemCount > 0 }?.getItemAt(0)

        /** 首个 item 的普通文本；空白文本交给 app 侧按空内容或 HTML fallback 处理。 */
        val text = firstItem?.text?.toString()

        /** 首个 item 的 HTML 文本；app 侧只会转纯文本，不持久化原始 HTML。 */
        val htmlText = firstItem?.htmlText

        /** ClipDescription 中的 MIME 类型列表；用于 app 侧区分空剪贴和不支持类型。 */
        val mimeTypes = clipData.description
            ?.let { description ->
                List(description.mimeTypeCount) { index -> description.getMimeType(index) }
            }
            .orEmpty()

        return ClipboardBridgeClipPayload(
            version = ClipboardBridgeContract.CLIP_PAYLOAD_VERSION,
            eventId = eventId,
            capturedAtMillis = capturedAtMillis,
            mimeTypes = mimeTypes,
            text = text,
            htmlText = htmlText
        )
    }

    /**
     * 构造隐藏 `IClipboard#getPrimaryClip` 的反射参数。
     *
     * @param method 当前系统暴露的 `getPrimaryClip` 方法。
     * @param userId 要读取的 Android 用户 ID；第一个 int 参数按 userId 处理，第二个 int 参数按 deviceId 处理。
     */
    internal fun buildGetPrimaryClipArgs(method: Method, userId: Int): Array<Any?> {
        /** String 参数序号；第一个 String 是 callingPackage，后续 String 视为 attributionTag。 */
        var stringArgIndex = 0
        /** Int 参数序号；第一个 Int 是 userId，第二个 Int 是 deviceId。 */
        var intArgIndex = 0

        /** 反射调用参数数组，按参数类型而不是固定版本签名构建，降低 Android 版本差异风险。 */
        val args = method.parameterTypes.map { parameterType ->
            when {
                parameterType == String::class.java -> {
                    if (stringArgIndex++ == 0) SHELL_CLIPBOARD_CALLING_PACKAGE else null
                }
                parameterType == Int::class.javaPrimitiveType -> {
                    if (intArgIndex++ == 0) userId else DEFAULT_CLIPBOARD_DEVICE_ID
                }
                else -> null
            }
        }
        return args.toTypedArray()
    }
}
