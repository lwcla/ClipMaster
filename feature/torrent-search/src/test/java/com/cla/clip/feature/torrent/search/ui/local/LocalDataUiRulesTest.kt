package com.cla.clip.feature.torrent.search.ui.local

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

/** 本地数据页面低敏身份派生与固定错误契约回归。 */
class LocalDataUiRulesTest {
    /** 已查看列表只能显示首尾缩略身份，不能泄漏完整 hash。 */
    @Test
    fun viewedIdentityUsesStableAbbreviation() {
        assertEquals("0123…6789", abbreviateInfoHash("0123456789012345678901234567890123456789"))
        assertEquals("••••", abbreviateInfoHash("short"))
    }

    /** 页面错误必须保持四个固定分类，不携带动态正文。 */
    @Test
    fun failuresRemainFixedAndContentFree() {
        assertEquals(
            listOf("READ_FAILED", "WRITE_FAILED", "IMPORT_FAILED", "EXPORT_FAILED"),
            LocalDataFailure.entries.map { /** failure 只读取固定枚举名用于契约断言。 */ failure -> failure.name },
        )
    }

    /** 书签 UI state 不得拥有 URI 字段，避免选择器授权被状态或保存机制持久化。 */
    @Test
    fun bookmarkStateCannotRetainSafUri() {
        /** retainsUri 检查状态契约是否意外新增 URI 类型字段。 */
        val retainsUri = BookmarksUiState::class.java.declaredFields.any {
            /** field 是状态类的真实 JVM 字段。 */
            field ->
            /** 当前字段类型只与 Android URI 类型比较，不读取字段值。 */
            Uri::class.java.isAssignableFrom(field.type)
        }

        assertFalse(retainsUri)
    }
}
