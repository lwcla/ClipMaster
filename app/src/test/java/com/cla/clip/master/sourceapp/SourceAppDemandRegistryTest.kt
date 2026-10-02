package com.cla.clip.master.sourceapp

import org.junit.Assert.assertEquals
import org.junit.Test

/** 聚合生命周期需求，保护同来源共享和跨页面释放边界。 */
class SourceAppDemandRegistryTest {
    /** 一个页面释放不能删除另一个页面仍需要的包名。 */
    @Test
    fun overlappingSessionsShareDemandUntilLastRelease() {
        /** 待实现的纯需求注册表，不依赖 Compose 或数据库。 */
        val registry = SourceAppDemandRegistry()
        registry.update("page-a", setOf("a", "shared"))
        registry.update("page-b", setOf("b", "shared"))
        assertEquals(setOf("a", "b", "shared"), registry.packages())
        registry.remove("page-a")
        assertEquals(setOf("b", "shared"), registry.packages())
        registry.remove("page-b")
        assertEquals(emptySet<String>(), registry.packages())
    }

    /** 同一个会话替换需求，不能累积历史包名。 */
    @Test
    fun updatesReplaceOldSessionDemandAndSkipBlankPackage() {
        /** 会话内切换当前可见项。 */
        val registry = SourceAppDemandRegistry()
        registry.update("page", setOf("old", ""))
        registry.update("page", setOf("new"))
        assertEquals(setOf("new"), registry.packages())
    }
}
