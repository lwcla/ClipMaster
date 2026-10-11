package com.cla.clip.master.provider

import com.cla.clip.shizuku.ClipboardBridgeContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 旧服务查询仍能解析成功响应，但不能要求传图或误报剪贴已保存。 */
class ClipboardBridgeIconCompatibilityTest {
    /** 图标查询/提交兼容结果不产生任何同步或入库确认。 */
    @Test
    fun disabledIconBridgeNeverRequestsUpload() {
        /** 旧调用方读取的稳定响应，不依赖 Android Bundle。 */
        val result = ClipboardBridgeIconCompatibility.result()
        assertEquals(ClipboardBridgeContract.CODE_OK, result.resultCode)
        assertEquals(false, result.shouldSyncIcon)
        assertEquals("memory_icons_only", result.iconDecisionReason)
        assertEquals(ClipboardBridgeContract.ICON_STATUS_PLACEHOLDER, result.iconStatus)
        assertFalse(result.saved)
        assertFalse(result.clipCommitted)
    }
}
