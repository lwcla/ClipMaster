package com.cla.clip.master.ui.widget.sourceapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 保护缓存失败后的系统兜底顺序；合成字符串代替平台 Drawable，不依赖设备或真实来源。 */
class SourceAppIconResolverTest {
    /** 缓存命中应展示缓存，不能改成优先查询系统而增加 IO。 */
    @Test
    fun cacheHitReturnsCachedIconWithoutReadingSystem() = runBlocking {
        /** 系统调用记录用于保护缓存命中时不产生副作用。 */
        var systemReads = 0
        /** 返回固定合成图标的实际解析器。 */
        val resolver = SourceAppIconResolver<String>({ _, _ -> "cached-icon" }, { systemReads++; "system-icon" })
        assertEquals("cached-icon", resolver.load("fixture.app", "fixture.png", "version-1"))
        assertEquals(0, systemReads)
    }

    /** 缓存缺失必须按稳定包名读取系统图标，防止直接进入默认图标。 */
    @Test
    fun missingCacheReturnsSystemIconForRequestedPackage() = runBlocking {
        /** 系统读取参数保护不能以应用显示名称查询。 */
        var requestedPackage: String? = null
        /** 空缓存读取不应调用，系统读取返回合成图标。 */
        val resolver = SourceAppIconResolver<String>(
            { _, _ -> error("Absent path must not be read") },
            { requestedPackage = it; "system-icon" },
        )
        assertEquals("system-icon", resolver.load("fixture.app", null, null))
        assertEquals("fixture.app", requestedPackage)
    }

    /** 缺失文件或解码失败都以 null 表达，必须继续尝试系统。 */
    @Test
    fun unreadableCacheFallsBackToSystemAfterCacheAttempt() = runBlocking {
        /** 顺序记录保护必须先尝试缓存，而不是无条件查询系统。 */
        val reads = mutableListOf<String>()
        /** 模拟平台层不可读/解码失败契约的实际解析器。 */
        val resolver = SourceAppIconResolver<String>(
            { _, _ -> reads += "cache"; null },
            { reads += "system"; "system-icon" },
        )
        assertEquals("system-icon", resolver.load("fixture.app", "broken.png", "version-2"))
        assertEquals(listOf("cache", "system"), reads)
    }

    /** 系统返回不可用后才允许最终默认图标，不能制造伪成功结果。 */
    @Test
    fun unavailableSystemReturnsNullAfterAttemptingBothSources() = runBlocking {
        /** 两层实际读取顺序，防止默认值掩盖未尝试系统的问题。 */
        val reads = mutableListOf<String>()
        /** 两层均失败的合成读取边界。 */
        val resolver = SourceAppIconResolver<String>(
            { _, _ -> reads += "cache"; null },
            { reads += "system"; null },
        )
        assertNull(resolver.load("fixture.missing", "broken.png", null))
        assertEquals(listOf("cache", "system"), reads)
    }

    /** 未知来源不能拿空包名访问系统，但仍允许其已有缓存显示。 */
    @Test
    fun unknownSourceSkipsSystemReadWhenCacheIsUnavailable() = runBlocking {
        /** 空包名的系统读取次数，保护未知来源边界。 */
        var systemReads = 0
        /** 未知来源缓存不可用的合成解析器。 */
        val resolver = SourceAppIconResolver<String>({ _, _ -> null }, { systemReads++; "system-icon" })
        assertNull(resolver.load("", null, null))
        assertEquals(0, systemReads)
    }

    /** 新 hash 必须交给缓存读取，以免同路径覆盖后继续使用旧图标。 */
    @Test
    fun cacheReaderReceivesPathAndHashUnchanged() = runBlocking {
        /** 缓存边界的路径与版本参数，用合成值保护透传。 */
        var requestedCache: Pair<String, String?>? = null
        /** 记录真实解析器传给缓存边界的参数。 */
        val resolver = SourceAppIconResolver<String>(
            { path, hash -> requestedCache = path to hash; "cached-icon" },
            { "system-icon" },
        )
        assertEquals("cached-icon", resolver.load("fixture.app", "fixture.png", "version-3"))
        assertEquals("fixture.png" to "version-3", requestedCache)
    }

    /** 菜单离开时的取消必须传播，不能降级后继续执行系统读取。 */
    @Test(expected = CancellationException::class)
    fun cancelledCacheReadPropagatesInsteadOfFallingBack() = runBlocking {
        /** 取消后的系统调用若执行会直接失败，保护停止副作用的契约。 */
        val resolver = SourceAppIconResolver<String>(
            { _, _ -> throw CancellationException("fixture cancellation") },
            { error("Cancelled read must not fall back") },
        )
        resolver.load("fixture.app", "fixture.png", null)
        Unit
    }
}
