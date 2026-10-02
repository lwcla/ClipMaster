package com.cla.clip.base.general.entity

import com.cla.clip.base.general.dao.ClipData
import com.cla.clip.base.general.dao.SourceAppData
import com.cla.clip.base.general.dao.data.ClipDetail
import com.cla.clip.base.general.dao.data.ClipPageData
import com.cla.clip.base.general.dao.LinkPreviewData
import org.junit.Assert.assertEquals
import org.junit.Test

/** 内容映射测试：来源展示快照不再属于分页或详情的内容模型。 */
class ClipShowEntityTest {

    /** 分页关系切换后仍须保留链接、置顶/折叠状态、时间和来源身份。 */
    @Test
    fun pageMappingPreservesContentAndLinkFields() {
        /** 覆盖公共内容契约的合成分页记录，不查询来源表。 */
        val page = ClipPageData(
            ClipData(id = 2, content = "内容", timestamp = 1000, sourceAppPackage = "fixture.app",
                pinnedTime = 2000, isFolded = true, foldedAt = 3000, deletedAt = 4000,
                link = "fixture-link", searchText = "内容"),
            LinkPreviewData("fixture-link", "标题", "描述", "fixture-image", null),
        )
        /** 固定时间文案避免 Android DateUtils 的 JVM 桩。 */
        val ui = page.toUiWithFormattedTime("固定时间")
        assertEquals("fixture.app", ui.sourceAppPackage)
        assertEquals("fixture-link", ui.link)
        assertEquals("fixture-image", ui.linkImgUrl)
        assertEquals("标题", ui.linkTitle)
        assertEquals(true, ui.isPinned)
        assertEquals(true, ui.isFolded)
        assertEquals(3000L, ui.foldedAt)
        assertEquals(4000L, ui.deletedAt)
        assertEquals("固定时间", ui.formattedTime)
        assertEquals(null, page.copy(clip = page.clip.copy(sourceAppPackage = " ")).toUiWithFormattedTime("固定时间").sourceAppPackage)
    }

    @Test
    /** 旧详情关系仍可用于后台查询，但转为 UI 内容时只保留来源身份。 */
    fun toUiKeepsSourceIdentityWithoutPresentationSnapshot() {
        /** 数据库中的剪贴记录。 */
        val clipData = ClipData(
            id = 1L,
            content = "clip",
            timestamp = 1_000L,
            link = null,
            sourceAppPackage = "com.example",
            searchText = "clip 示例应用 com.example"
        )

        /** 数据库中的来源 App 缓存。 */
        val sourceAppData = SourceAppData(
            packageName = "com.example",
            appName = "示例应用",
            iconPath = "/icons/example.png",
            primaryColor = null,
            iconHash = "stable-hash"
        )

        /** Room 查询得到的组合实体。 */
        val clipDetail = ClipDetail(
            clip = clipData,
            sourceApp = sourceAppData,
            linkPreview = null
        )

        /** UI 层使用的扁平展示实体；测试传入固定时间文案以避开 Android DateUtils JVM stub。 */
        val ui = clipDetail.toUiWithFormattedTime("刚刚")

        assertEquals("com.example", ui.sourceAppPackage)
        assertEquals("clip", ui.content)
        assertEquals(false, ui.javaClass.declaredFields.any { it.name in setOf("appName", "appIconPath", "appIconHash", "appColor") })
    }
}
