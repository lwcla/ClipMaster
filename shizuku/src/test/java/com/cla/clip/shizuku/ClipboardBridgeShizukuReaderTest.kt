package com.cla.clip.shizuku

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Shizuku 系统剪贴板读取器测试，保护隐藏 IClipboard 参数构造兼容性。 */
class ClipboardBridgeShizukuReaderTest {
    /** 被测读取器；测试只调用纯参数构造方法，不触碰真实系统剪贴板。 */
    private val reader = ShizukuClipboardReader()

    @Test
    /** Android 12 及更早常见签名只需要 callingPackage 和 userId。 */
    fun buildGetPrimaryClipArgsSupportsPackageAndUserSignature() {
        /** 模拟旧版 getPrimaryClip(String, int) 签名的方法。 */
        val method = FakeClipboardApi::class.java.getDeclaredMethod(
            "getPrimaryClipOld",
            String::class.java,
            Int::class.javaPrimitiveType
        )

        /** 构造出的反射参数，必须使用 shell calling package 和传入 userId。 */
        val args = reader.buildGetPrimaryClipArgs(method, userId = 10)

        assertArrayEquals(arrayOf<Any?>(ShizukuClipboardReader.SHELL_CLIPBOARD_CALLING_PACKAGE, 10), args)
    }

    @Test
    /** Android 13+ 常见签名需要 attributionTag 和 deviceId 时应填入安全默认值。 */
    fun buildGetPrimaryClipArgsSupportsAttributionAndDeviceSignature() {
        /** 模拟新版 getPrimaryClip(String, String, int, int) 签名的方法。 */
        val method = FakeClipboardApi::class.java.getDeclaredMethod(
            "getPrimaryClipNew",
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )

        /** 构造出的反射参数，第二个 String 是 attributionTag，第二个 Int 是默认 deviceId。 */
        val args = reader.buildGetPrimaryClipArgs(method, userId = 0)

        assertEquals(ShizukuClipboardReader.SHELL_CLIPBOARD_CALLING_PACKAGE, args[0])
        assertEquals(null, args[1])
        assertEquals(0, args[2])
        assertEquals(0, args[3])
    }

    /** 反射测试专用假 API，只提供方法签名，不参与真实调用。 */
    private class FakeClipboardApi {
        /** 模拟旧版隐藏 API 签名。 */
        @Suppress("unused")
        fun getPrimaryClipOld(packageName: String, userId: Int): Any? = null

        /** 模拟新版隐藏 API 签名。 */
        @Suppress("unused")
        fun getPrimaryClipNew(packageName: String, attributionTag: String?, userId: Int, deviceId: Int): Any? = null
    }
}
