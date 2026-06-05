package com.cla.clip.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 来源应用解析器测试，保护包名异常时不阻断剪贴 payload 提交。 */
class ShizukuSourceAppResolverTest {

    @Test
    /** 来源包名为空时应直接回退 Unknown 和空图标。 */
    fun resolveReturnsUnknownWhenPackageBlank() {
        /** 待读取次数；空包名时不应调用 reader。 */
        var readCount = 0
        /** 待测来源解析器。 */
        val resolver = ShizukuSourceAppResolver(
            sourceAppReader = ShizukuSourceAppReader {
                readCount += 1
                ShizukuSourceAppReadResult(appName = "App", bitmap = null)
            }
        )

        /** 解析结果。 */
        val result = resolver.resolve("")

        assertEquals(0, readCount)
        assertEquals("Unknown", result.appName)
        assertNull(result.bitmap)
        assertNull(result.iconHash)
    }

    @Test
    /** 正常读取来源应用名时应保留原始包名和展示名。 */
    fun resolveReturnsReaderAppName() {
        /** 待测来源解析器，使用空图标避免 JVM 测试依赖 Android Bitmap。 */
        val resolver = ShizukuSourceAppResolver(
            sourceAppReader = ShizukuSourceAppReader {
                ShizukuSourceAppReadResult(appName = "Source App", bitmap = null)
            }
        )

        /** 解析结果。 */
        val result = resolver.resolve("com.demo.source")

        assertEquals("com.demo.source", result.packageName)
        assertEquals("Source App", result.appName)
        assertNull(result.bitmap)
        assertNull(result.iconHash)
    }

    @Test
    /** PackageManager 异常或卸载竞态时应回退 Unknown，不向调用方抛出异常。 */
    fun resolveFallsBackWhenReaderThrows() {
        /** 待测来源解析器，模拟来源包不可见。 */
        val resolver = ShizukuSourceAppResolver(
            sourceAppReader = ShizukuSourceAppReader {
                error("package missing")
            }
        )

        /** 解析结果。 */
        val result = resolver.resolve("com.missing")

        assertEquals("com.missing", result.packageName)
        assertEquals("Unknown", result.appName)
        assertNull(result.bitmap)
        assertNull(result.iconHash)
    }
}
