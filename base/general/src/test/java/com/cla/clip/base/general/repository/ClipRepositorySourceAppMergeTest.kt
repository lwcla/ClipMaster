package com.cla.clip.base.general.repository

import com.cla.clip.base.general.dao.ClipData
import com.cla.clip.base.general.dao.SourceAppData
import com.cla.clip.base.general.dao.data.ClipDetail
import com.cla.clip.base.general.dao.data.LastClipData
import com.cla.clip.base.general.entity.ClipCaptureEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 来源 App 缓存合并测试，保护 Provider 异步补图不会被后续轻量入库覆盖。 */
class ClipRepositorySourceAppMergeTest {

    @Test
    /** commit_clip 没有携带图标字段时，应保留数据库中已经补齐的图标缓存。 */
    fun buildSourceAppForClipKeepsExistingIconWhenCaptureHasNoIcon() {
        /** 数据库中已补齐图标的来源 App。 */
        val existingSourceApp = SourceAppData(
            packageName = "com.example",
            appName = "旧名称",
            iconPath = "/old/icon.png",
            primaryColor = 0x112233,
            iconHash = "old-hash"
        )

        /** Provider 轻量入库传入的捕获数据，不等待图标写入完成。 */
        val captureEntity = captureEntity(
            sourcePackage = "com.example",
            sourceAppName = "新名称",
            iconPath = null,
            iconHash = null,
            primaryColor = null
        )

        /** 合并后的来源 App 缓存。 */
        val sourceApp = buildSourceAppForClip(captureEntity, existingSourceApp)

        assertEquals("新名称", sourceApp.appName)
        assertEquals("/old/icon.png", sourceApp.iconPath)
        assertEquals(0x112233, sourceApp.primaryColor)
        assertEquals("old-hash", sourceApp.iconHash)
    }

    @Test
    /** 同步模式或旧 AIDL 携带新图标字段时，应使用本次捕获的新图标信息。 */
    fun buildSourceAppForClipUsesCaptureIconWhenPresent() {
        /** 数据库中已有的旧来源 App 图标。 */
        val existingSourceApp = SourceAppData(
            packageName = "com.example",
            appName = "旧名称",
            iconPath = "/old/icon.png",
            primaryColor = 0x112233,
            iconHash = "old-hash"
        )

        /** 带新图标字段的捕获数据。 */
        val captureEntity = captureEntity(
            sourcePackage = "com.example",
            sourceAppName = "新名称",
            iconPath = "/new/icon.png",
            iconHash = "new-hash",
            primaryColor = 0x445566
        )

        /** 合并后的来源 App 缓存。 */
        val sourceApp = buildSourceAppForClip(captureEntity, existingSourceApp)

        assertEquals("/new/icon.png", sourceApp.iconPath)
        assertEquals(0x445566, sourceApp.primaryColor)
        assertEquals("new-hash", sourceApp.iconHash)
    }

    @Test
    /** 首次轻量入库且没有图标时，图标字段应保持为空等待 commit_icon 后续补齐。 */
    fun buildSourceAppForClipKeepsIconEmptyWithoutExistingCache() {
        /** Provider 轻量入库传入的首次来源数据。 */
        val captureEntity = captureEntity(
            sourcePackage = "com.example",
            sourceAppName = "示例应用",
            iconPath = null,
            iconHash = null,
            primaryColor = null
        )

        /** 合并后的来源 App 缓存。 */
        val sourceApp = buildSourceAppForClip(captureEntity, existingSourceApp = null)

        assertEquals("示例应用", sourceApp.appName)
        assertNull(sourceApp.iconPath)
        assertNull(sourceApp.primaryColor)
        assertNull(sourceApp.iconHash)
    }

    @Test
    /** 坏路径被 query_icon_state 清空后，后续轻量入库不应再把旧坏路径重新写回。 */
    fun buildSourceAppForClipKeepsClearedIconCacheEmpty() {
        /** query_icon_state 已清空旧图标字段后的来源缓存。 */
        val existingSourceApp = SourceAppData(
            packageName = "com.example",
            appName = "旧名称",
            iconPath = null,
            primaryColor = null,
            iconHash = null
        )

        /** 不携带新图标字段的轻量入库捕获数据。 */
        val captureEntity = captureEntity(
            sourcePackage = "com.example",
            sourceAppName = "新名称",
            iconPath = null,
            iconHash = null,
            primaryColor = null
        )

        /** 合并后的来源 App 缓存。 */
        val sourceApp = buildSourceAppForClip(captureEntity, existingSourceApp)

        assertEquals("新名称", sourceApp.appName)
        assertNull(sourceApp.iconPath)
        assertNull(sourceApp.primaryColor)
        assertNull(sourceApp.iconHash)
    }

    @Test
    /** 本次来源名称为 Unknown 时，不应覆盖数据库里同包名已经解析出的真实名称。 */
    fun buildSourceAppForClipKeepsExistingKnownNameWhenCaptureNameUnknown() {
        /** 数据库中已有真实名称的来源缓存。 */
        val existingSourceApp = SourceAppData(
            packageName = "com.example",
            appName = "真实应用",
            iconPath = "/old/icon.png",
            primaryColor = null,
            iconHash = null
        )

        /** Shizuku 本次只解析出 Unknown 名称的轻量捕获数据。 */
        val captureEntity = captureEntity(
            sourcePackage = "com.example",
            sourceAppName = "Unknown",
            iconPath = null,
            iconHash = null,
            primaryColor = null
        )

        /** 合并后的来源 App 缓存。 */
        val sourceApp = buildSourceAppForClip(captureEntity, existingSourceApp)

        assertEquals("真实应用", sourceApp.appName)
    }

    @Test
    /** commit_icon 的 appName 为空时，应保留旧名称并只更新图标字段。 */
    fun buildSourceAppIconUpdateKeepsExistingNameWhenNewNameBlank() {
        /** 数据库中已有的来源 App 缓存。 */
        val existingSourceApp = SourceAppData(
            packageName = "com.example",
            appName = "旧名称",
            iconPath = "/old/icon.png",
            primaryColor = 0x112233,
            iconHash = "old-hash"
        )

        /** 图标补全后的来源 App 缓存。 */
        val sourceApp = buildSourceAppIconUpdate(
            packageName = "com.example",
            appName = " ",
            iconPath = "/new/icon.png",
            primaryColor = 0x445566,
            iconHash = "new-hash",
            existingSourceApp = existingSourceApp
        )

        assertEquals("旧名称", sourceApp.appName)
        assertEquals("/new/icon.png", sourceApp.iconPath)
        assertEquals(0x445566, sourceApp.primaryColor)
        assertEquals("new-hash", sourceApp.iconHash)
    }

    @Test
    /** commit_icon 首次创建来源缓存且名称为空时，应使用包名作为可读兜底。 */
    fun buildSourceAppIconUpdateFallsBackToPackageNameWhenNameMissing() {
        /** 图标补全后的来源 App 缓存。 */
        val sourceApp = buildSourceAppIconUpdate(
            packageName = "com.example",
            appName = null,
            iconPath = "/new/icon.png",
            primaryColor = null,
            iconHash = "new-hash",
            existingSourceApp = null
        )

        assertEquals("com.example", sourceApp.appName)
        assertEquals("/new/icon.png", sourceApp.iconPath)
        assertNull(sourceApp.primaryColor)
        assertEquals("new-hash", sourceApp.iconHash)
    }

    @Test
    /** 重复剪贴内容更新时，应使用捕获时间刷新列表顺序并保留用户折叠/置顶状态。 */
    fun buildDuplicateClipUpdateUsesCapturedTimeAndKeepsUserState() {
        /** 本次捕获构造出的新剪贴实体，代表 Shizuku payload 已携带 capturedAtMillis。 */
        val newClip = clipData(
            id = 0L,
            timestamp = 9_000L,
            pinnedTime = 0L,
            isFolded = false,
            foldedAt = 0L
        )
        /** 数据库中已有的重复剪贴实体，包含用户已经设置过的状态。 */
        val existingClip = clipData(
            id = 42L,
            timestamp = 1_000L,
            pinnedTime = 2_000L,
            isFolded = true,
            foldedAt = 3_000L
        )

        /** 合并后的重复剪贴更新实体。 */
        val updatedClip = buildDuplicateClipUpdate(
            newClip = newClip,
            existingClip = existingClip,
            capturedAtMillis = 4_000L
        )

        assertEquals(42L, updatedClip.id)
        assertEquals(2_000L, updatedClip.pinnedTime)
        assertEquals(true, updatedClip.isFolded)
        assertEquals(3_000L, updatedClip.foldedAt)
        assertEquals(4_000L, updatedClip.timestamp)
    }

    @Test
    /** 已有未知来源候选遇到本次明确来源时，应更新旧记录完成来源升级。 */
    fun decideDuplicateClipSaveActionUpdatesUnknownCandidateWhenIncomingSourceKnown() {
        /** 已有的未知来源候选，等待后续明确来源覆盖。 */
        val unknownCandidate = clipDetail(id = 10L, packageName = "", appName = "")

        /** 本次同内容保存动作决策。 */
        val action = decideDuplicateClipSaveAction(
            candidates = listOf(unknownCandidate),
            incomingSourcePackage = "com.example.app",
            incomingSourceAppName = "示例应用"
        )

        assertTrue(action is DuplicateClipSaveAction.UpdateExisting)
        assertSame(unknownCandidate, (action as DuplicateClipSaveAction.UpdateExisting).existingClip)
    }

    @Test
    /** 已有明确 App A 时，本次明确 App B 复制同内容应新增一条记录。 */
    fun decideDuplicateClipSaveActionInsertsWhenDifferentKnownSourceExists() {
        /** 已有明确来源 App A 的候选。 */
        val knownCandidate = clipDetail(id = 11L, packageName = "com.example.a", appName = "应用A")

        /** 本次同内容保存动作决策。 */
        val action = decideDuplicateClipSaveAction(
            candidates = listOf(knownCandidate),
            incomingSourcePackage = "com.example.b",
            incomingSourceAppName = "应用B"
        )

        assertSame(DuplicateClipSaveAction.InsertNew, action)
    }

    @Test
    /** 已有明确来源时，本次未知来源不应新增未知重复记录，也不应覆盖明确来源。 */
    fun decideDuplicateClipSaveActionSkipsWhenIncomingSourceUnknownAndKnownSourceExists() {
        /** 已有明确来源候选。 */
        val knownCandidate = clipDetail(id = 12L, packageName = "com.example.a", appName = "应用A")

        /** 本次同内容保存动作决策。 */
        val action = decideDuplicateClipSaveAction(
            candidates = listOf(knownCandidate),
            incomingSourcePackage = "",
            incomingSourceAppName = ""
        )

        assertTrue(action is DuplicateClipSaveAction.SkipDuplicate)
        assertSame(knownCandidate, (action as DuplicateClipSaveAction.SkipDuplicate).existingClip)
    }

    @Test
    /** 已有包名但名称为 Unknown 时，应视为未知并允许后续明确来源覆盖。 */
    fun decideDuplicateClipSaveActionTreatsUnknownNameAsUnknownCandidate() {
        /** 包名存在但名称不可信的候选。 */
        val unknownNameCandidate = clipDetail(id = 13L, packageName = "com.example.old", appName = "Unknown")

        /** 本次同内容保存动作决策。 */
        val action = decideDuplicateClipSaveAction(
            candidates = listOf(unknownNameCandidate),
            incomingSourcePackage = "com.example.new",
            incomingSourceAppName = "新应用"
        )

        assertTrue(action is DuplicateClipSaveAction.UpdateExisting)
        assertSame(unknownNameCandidate, (action as DuplicateClipSaveAction.UpdateExisting).existingClip)
    }

    @Test
    /** 多个候选并存时，同包名候选优先于未知来源候选，确保链接预览二次保存命中同一条记录。 */
    fun decideDuplicateClipSaveActionPrefersSamePackageCandidateBeforeUnknownCandidate() {
        /** 同内容下较新的未知来源候选。 */
        val unknownCandidate = clipDetail(id = 20L, packageName = "", appName = "")
        /** 同内容下与本次来源包名相同的候选。 */
        val samePackageCandidate = clipDetail(id = 19L, packageName = "com.example.app", appName = "示例应用")

        /** 本次同内容保存动作决策。 */
        val action = decideDuplicateClipSaveAction(
            candidates = listOf(unknownCandidate, samePackageCandidate),
            incomingSourcePackage = "com.example.app",
            incomingSourceAppName = "示例应用"
        )

        assertTrue(action is DuplicateClipSaveAction.UpdateExisting)
        assertSame(samePackageCandidate, (action as DuplicateClipSaveAction.UpdateExisting).existingClip)
    }

    @Test
    /** 已有未知来源且本次仍未知时，应更新同一未知记录，避免链接预览二次保存新增重复记录。 */
    fun decideDuplicateClipSaveActionUpdatesUnknownCandidateWhenIncomingSourceAlsoUnknown() {
        /** 已有未知来源候选。 */
        val unknownCandidate = clipDetail(id = 21L, packageName = "", appName = "")

        /** 本次同内容保存动作决策。 */
        val action = decideDuplicateClipSaveAction(
            candidates = listOf(unknownCandidate),
            incomingSourcePackage = "",
            incomingSourceAppName = ""
        )

        assertTrue(action is DuplicateClipSaveAction.UpdateExisting)
        assertSame(unknownCandidate, (action as DuplicateClipSaveAction.UpdateExisting).existingClip)
    }

    @Test
    /** 连续重复预过滤遇到“上一条未知、本次明确”时必须放行，让 Repository 覆盖升级旧记录。 */
    fun shouldSkipConsecutiveDuplicateClipAllowsKnownSourceToUpgradeUnknownLastClip() {
        /** 最近一条未知来源记录。 */
        val lastClip = LastClipData(
            content = "clip",
            sourceAppPackage = "",
            sourceAppName = ""
        )

        /** 是否应该在 ClipHelper 前置阶段跳过。 */
        val skip = shouldSkipConsecutiveDuplicateClip(
            currentContent = "clip",
            currentSourcePackage = "com.example.app",
            currentSourceAppName = "示例应用",
            lastClip = lastClip
        )

        assertFalse(skip)
    }

    @Test
    /** 连续重复预过滤遇到“本次未知、上一条明确”时应跳过，避免前台读取制造未知来源重复。 */
    fun shouldSkipConsecutiveDuplicateClipSkipsUnknownIncomingAfterKnownLastClip() {
        /** 最近一条明确来源记录。 */
        val lastClip = LastClipData(
            content = "clip",
            sourceAppPackage = "com.example.app",
            sourceAppName = "示例应用"
        )

        /** 是否应该在 ClipHelper 前置阶段跳过。 */
        val skip = shouldSkipConsecutiveDuplicateClip(
            currentContent = "clip",
            currentSourcePackage = null,
            currentSourceAppName = null,
            lastClip = lastClip
        )

        assertTrue(skip)
    }

    /**
     * 构造测试用剪贴捕获实体。
     *
     * @param sourcePackage 来源应用包名。
     * @param sourceAppName 来源应用名称。
     * @param iconPath 来源应用图标路径。
     * @param iconHash 来源应用图标 hash。
     * @param primaryColor 来源应用图标主色。
     */
    private fun captureEntity(
        sourcePackage: String,
        sourceAppName: String,
        iconPath: String?,
        iconHash: String?,
        primaryColor: Int?,
    ): ClipCaptureEntity {
        return ClipCaptureEntity(
            content = "clip",
            timestamp = 1_000L,
            sourcePackage = sourcePackage,
            sourceAppName = sourceAppName,
            sourceAppIconPath = iconPath,
            sourceAppIconHash = iconHash,
            sourcePrimaryColor = primaryColor,
            link = null,
            linkTitle = null,
            linkDescription = null,
            linkImageUrl = null,
            linkSiteName = null
        )
    }

    /**
     * 构造测试用剪贴数据库实体。
     *
     * @param id 剪贴记录 id。
     * @param timestamp 列表排序时间。
     * @param pinnedTime 置顶时间。
     * @param isFolded 是否折叠。
     * @param foldedAt 折叠时间。
     */
    private fun clipData(
        id: Long,
        timestamp: Long,
        pinnedTime: Long,
        isFolded: Boolean,
        foldedAt: Long,
    ): ClipData {
        return ClipData(
            id = id,
            content = "clip",
            timestamp = timestamp,
            pinnedTime = pinnedTime,
            isFolded = isFolded,
            foldedAt = foldedAt,
            link = null,
            sourceAppPackage = "com.example",
            searchText = "clip"
        )
    }

    /**
     * 构造测试用剪贴详情候选。
     *
     * @param id 剪贴记录 id。
     * @param packageName 来源包名，空字符串表示未知来源。
     * @param appName 来源应用名，空白或 Unknown 表示名称不可信。
     */
    private fun clipDetail(
        id: Long,
        packageName: String,
        appName: String,
    ): ClipDetail {
        return ClipDetail(
            clip = ClipData(
                id = id,
                content = "clip",
                timestamp = 1_000L + id,
                link = null,
                sourceAppPackage = packageName,
                searchText = "clip"
            ),
            sourceApp = packageName.takeIf { it.isNotEmpty() }?.let { sourcePackage ->
                SourceAppData(
                    packageName = sourcePackage,
                    appName = appName,
                    iconPath = null,
                    primaryColor = null,
                    iconHash = null
                )
            },
            linkPreview = null
        )
    }
}
