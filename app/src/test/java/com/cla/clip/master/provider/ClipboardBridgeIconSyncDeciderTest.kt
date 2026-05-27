package com.cla.clip.master.provider

import com.cla.clip.base.general.dao.SourceAppData
import com.cla.clip.shizuku.ClipboardBridgeContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/** 图标同步决策器测试，保护 query_icon_state 和 commit_icon 共用的缓存命中语义。 */
class ClipboardBridgeIconSyncDeciderTest {
    /** 被测图标同步决策器。 */
    private val decider = ClipboardBridgeIconSyncDecider()

    @Test
    /** 数据库 hash 命中且文件存在时，不应继续同步图标。 */
    fun decideReturnsCacheHitWhenHashMatchesAndFileExists() {
        /** 当前测试使用的真实临时图标文件。 */
        val iconFile = createTempDirectory(prefix = "icon_cache_hit").toFile().resolve("icon.png").apply {
            parentFile?.mkdirs()
            writeText("png")
        }

        try {
            /** 已命中缓存的来源 App。 */
            val sourceAppData = SourceAppData(
                packageName = "com.example",
                appName = "示例应用",
                iconPath = iconFile.absolutePath,
                primaryColor = 0x112233,
                iconHash = "same-hash"
            )

            /** 当前来源图标同步决策。 */
            val decision = decider.decide(sourceAppData, "same-hash")

            assertFalse(decision.shouldSyncIcon)
            assertFalse(decision.clearStaleCache)
            assertEquals(ClipboardBridgeContract.ICON_REASON_CACHE_HIT, decision.reasonCode)
        } finally {
            iconFile.parentFile?.deleteRecursively()
        }
    }

    @Test
    /** 数据库 hash 命中但图标文件缺失时，应清空坏缓存并继续同步。 */
    fun decideReturnsStaleFileMissingWhenHashMatchesButFileMissing() {
        /** 指向一个并不存在的旧图标路径。 */
        val sourceAppData = SourceAppData(
            packageName = "com.example",
            appName = "示例应用",
            iconPath = File("/tmp/not-found-icon.png").absolutePath,
            primaryColor = 0x112233,
            iconHash = "same-hash"
        )

        /** 当前来源图标同步决策。 */
        val decision = decider.decide(sourceAppData, "same-hash")

        assertTrue(decision.shouldSyncIcon)
        assertTrue(decision.clearStaleCache)
        assertEquals(ClipboardBridgeContract.ICON_REASON_STALE_FILE_MISSING, decision.reasonCode)
    }

    @Test
    /** 数据库 hash 与当前图标 hash 不同时，应继续同步并覆盖旧图标。 */
    fun decideReturnsHashChangedWhenHashDiffers() {
        /** 当前数据库中已有的来源图标缓存。 */
        val sourceAppData = SourceAppData(
            packageName = "com.example",
            appName = "示例应用",
            iconPath = "/cached/icon.png",
            primaryColor = 0x112233,
            iconHash = "old-hash"
        )

        /** 当前来源图标同步决策。 */
        val decision = decider.decide(sourceAppData, "new-hash")

        assertTrue(decision.shouldSyncIcon)
        assertFalse(decision.clearStaleCache)
        assertEquals(ClipboardBridgeContract.ICON_REASON_HASH_CHANGED, decision.reasonCode)
    }

    @Test
    /** 数据库无缓存时，应要求继续同步来源图标。 */
    fun decideReturnsNoCachedIconWhenSourceAppMissing() {
        /** 当前来源图标同步决策。 */
        val decision = decider.decide(sourceAppData = null, requestIconHash = "new-hash")

        assertTrue(decision.shouldSyncIcon)
        assertFalse(decision.clearStaleCache)
        assertEquals(ClipboardBridgeContract.ICON_REASON_NO_CACHED_ICON, decision.reasonCode)
    }

    @Test
    /** 没有图标 hash 时，不需要继续同步来源图标。 */
    fun decideReturnsNoIconAvailableWhenHashMissing() {
        /** 当前来源图标同步决策。 */
        val decision = decider.decide(sourceAppData = null, requestIconHash = null)

        assertFalse(decision.shouldSyncIcon)
        assertFalse(decision.clearStaleCache)
        assertEquals(ClipboardBridgeContract.ICON_REASON_NO_ICON_AVAILABLE, decision.reasonCode)
    }
}
