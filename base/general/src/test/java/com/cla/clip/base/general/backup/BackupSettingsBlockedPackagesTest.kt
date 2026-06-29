package com.cla.clip.base.general.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 来源 App 过滤名单备份协议测试。
 *
 * 这里只验证 settings JSON 的字段兼容性，恢复并集逻辑由 `BackupSnapshotRestorer` 在集成路径中消费同一字段。
 */
class BackupSettingsBlockedPackagesTest {

    /** 旧备份缺少来源过滤字段时必须回退为空名单，避免恢复历史备份失败。 */
    @Test
    fun decodeOldSettingsWithoutBlockedPackagesUsesEmptyList() {
        /** 历史版本 settings JSON；没有 blocked_clip_source_packages 字段。 */
        val legacyJson = """{"clip_item_quick_action":"copy","recycle_bin_retention_days":30}"""
        /** 解码后的设置模型；新增字段应使用默认空列表。 */
        val settings = BackupJson.decodeSettings(legacyJson)

        assertTrue(settings.blockedClipSourcePackages.isEmpty())
    }

    /** 新备份必须显式写入 blocked_clip_source_packages，方便跨安装恢复来源过滤规则。 */
    @Test
    fun encodeSettingsWritesBlockedPackagesField() {
        /** 待导出的设置模型；只保存包名，不保存 App 名称或安装列表。 */
        val settings = BackupSettings(blockedClipSourcePackages = listOf("com.demo.alpha", "com.demo.beta"))
        /** 编码后的 settings JSON；encodeDefaults 会显式写出新增协议字段。 */
        val json = BackupJson.encodeSettings(settings)
        /** 回读后的设置模型；用于确认协议字段可逆。 */
        val decoded = BackupJson.decodeSettings(json)

        assertTrue(json.contains("\"blocked_clip_source_packages\""))
        assertEquals(listOf("com.demo.alpha", "com.demo.beta"), decoded.blockedClipSourcePackages)
    }
}
