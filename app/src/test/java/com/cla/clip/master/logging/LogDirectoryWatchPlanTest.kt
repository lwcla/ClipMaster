package com.cla.clip.master.logging

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 逐级创建与目录重建都保持父锚点，防止一次绑定丢失后续实际数量变化。 */
class LogDirectoryWatchPlanTest {
    /** 所有删除/移动仅针对 JUnit 自建的合成目录。 */
    @get:Rule val temporary = TemporaryFolder()

    /** 父目录缺失时先监测 Download，再随事件增补新建的父/目标目录。 */
    @Test fun missingDirectoriesAcquireWatchesAsTheyAppear() {
        /** 合成 Download 锚点，不访问真实共享存储。 */
        val downloads = temporary.newFolder("Download").canonicalFile
        /** 逐级创建的应用根目录。 */
        val appFolder = File(downloads, "ClipMaster")
        /** 固定目标目录始终可预测。 */
        val logs = File(appFolder, "logs")
        assertEquals(listOf(downloads), LogDirectoryWatchPlan.paths(logs))
        appFolder.mkdir()
        assertEquals(listOf(appFolder, downloads), LogDirectoryWatchPlan.paths(logs))
        logs.mkdir()
        assertEquals(listOf(logs, appFolder, downloads), LogDirectoryWatchPlan.paths(logs))
    }

    /** 目标移出或删除后保留父锚点，重新创建后恢复目标监测。 */
    @Test fun removedOrMovedTargetCanBeWatchedAfterRecreation() {
        /** 合成共享存储的三层结构。 */
        val downloads = temporary.newFolder("Download").canonicalFile
        /** 始终存在的中间父目录。 */
        val appFolder = File(downloads, "ClipMaster").apply { mkdir() }
        /** 目标只含合成空目录，可以安全移动/删除。 */
        val logs = File(appFolder, "logs").apply { mkdir() }
        logs.renameTo(File(appFolder, "moved"))
        assertEquals(listOf(appFolder, downloads), LogDirectoryWatchPlan.paths(logs))
        logs.mkdir()
        assertEquals(listOf(logs, appFolder, downloads), LogDirectoryWatchPlan.paths(logs))
        logs.delete()
        assertEquals(listOf(appFolder, downloads), LogDirectoryWatchPlan.paths(logs))
        logs.mkdir()
        File(logs, "clipmaster.txt").createNewFile()
        assertEquals(listOf(logs, appFolder, downloads), LogDirectoryWatchPlan.paths(logs))
        assertEquals(1, LogDirectoryMetadataReader.count(logs))
    }
}
