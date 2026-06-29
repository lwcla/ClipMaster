package com.cla.clip.base.general.entity

import com.cla.clip.base.general.dao.ClipData
import com.cla.clip.base.general.dao.SourceAppData
import com.cla.clip.base.general.dao.data.ClipDetail
import org.junit.Assert.assertEquals
import org.junit.Test

/** 剪贴展示实体映射测试，保护来源图标 hash 能进入 UI 刷新 key。 */
class ClipShowEntityTest {

    @Test
    /** ClipDetail 转 UI 时应携带来源 App 的 iconHash。 */
    fun toUiMapsSourceAppIconHash() {
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

        assertEquals("/icons/example.png", ui.appIconPath)
        assertEquals("stable-hash", ui.appIconHash)
        assertEquals("com.example", ui.sourceAppPackage)
    }
}
