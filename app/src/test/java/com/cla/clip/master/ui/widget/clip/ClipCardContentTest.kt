package com.cla.clip.master.ui.widget.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 剪贴卡片来源图标缓存 key 测试，保护异步补图后同一路径能刷新 UI。 */
class ClipCardContentTest {

    @Test
    /** 同一路径下 iconHash 变化时，Coil 缓存 key 必须随之变化。 */
    fun buildSourceAppIconCacheKeyChangesWhenHashChanges() {
        /** 图标缓存文件路径，commit_icon 可能覆盖同一路径的实际内容。 */
        val iconPath = "/files/icons/com.example.png"

        /** 旧图标对应的 Coil 缓存 key。 */
        val oldKey = buildSourceAppIconCacheKey(iconPath, "old-hash")
        /** 新图标对应的 Coil 缓存 key。 */
        val newKey = buildSourceAppIconCacheKey(iconPath, "new-hash")

        assertNotEquals(oldKey, newKey)
    }

    @Test
    /** iconHash 缺失时仍应生成稳定 key，保证占位或旧数据路径可正常加载。 */
    fun buildSourceAppIconCacheKeyHandlesMissingHash() {
        /** 图标缓存文件路径。 */
        val iconPath = "/files/icons/com.example.png"

        assertEquals("$iconPath:", buildSourceAppIconCacheKey(iconPath, null))
    }
}
