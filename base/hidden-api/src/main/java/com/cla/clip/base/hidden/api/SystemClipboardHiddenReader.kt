package com.cla.clip.base.hidden.api

import android.content.ClipData
import android.content.Context
import android.os.IBinder
import java.lang.reflect.Method

/**
 * 系统剪贴板隐藏 API 读取器。
 *
 * 该类只负责 `ServiceManager`、`IClipboard.Stub` 和 `IClipboard#getPrimaryClip` 的隐藏 API 访问，不决定调用方身份是否合法，也不做业务 payload 映射。
 */
class SystemClipboardHiddenReader {
    companion object {
        /** 默认设备 ID；Android 13+ `IClipboard` 签名会要求传入默认设备剪贴板。 */
        private const val DEFAULT_CLIPBOARD_DEVICE_ID = 0
    }

    /**
     * 读取系统当前主剪贴板。
     *
     * @param callingPackage 传给系统剪贴板服务的调用包名；调用方需要确保它与当前进程 uid/权限语义匹配。
     * @param userId 要读取的 Android 用户 ID；第一个 int 参数按 userId 处理。
     * @return 读取成功时返回系统 `ClipData`；服务不存在、签名不匹配或反射失败时返回 null。
     */
    fun readPrimaryClip(
        callingPackage: String,
        userId: Int = 0,
    ): ClipData? {
        return runCatching {
            HiddenApiExemptions.addIfNeeded(
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
            val getPrimaryClipArgs = buildGetPrimaryClipArgs(
                method = getPrimaryClipMethod,
                callingPackage = callingPackage,
                userId = userId
            )
            getPrimaryClipMethod.invoke(clipboardService, *getPrimaryClipArgs) as? ClipData
        }.getOrNull()
    }

    /**
     * 构造隐藏 `IClipboard#getPrimaryClip` 的反射参数。
     *
     * @param method 当前系统暴露的 `getPrimaryClip` 方法。
     * @param callingPackage 传给系统剪贴板服务的调用包名；第一个 String 参数按 callingPackage 处理。
     * @param userId 要读取的 Android 用户 ID；第一个 int 参数按 userId 处理，第二个 int 参数按 deviceId 处理。
     */
    fun buildGetPrimaryClipArgs(
        method: Method,
        callingPackage: String,
        userId: Int,
    ): Array<Any?> {
        /** String 参数序号；第一个 String 是 callingPackage，后续 String 视为 attributionTag。 */
        var stringArgIndex = 0
        /** Int 参数序号；第一个 Int 是 userId，第二个 Int 是 deviceId。 */
        var intArgIndex = 0

        /** 反射调用参数数组，按参数类型而不是固定版本签名构建，降低 Android 版本差异风险。 */
        val args = method.parameterTypes.map { parameterType ->
            when {
                parameterType == String::class.java -> {
                    if (stringArgIndex++ == 0) callingPackage else null
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
