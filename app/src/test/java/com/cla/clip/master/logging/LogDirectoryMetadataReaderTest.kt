package com.cla.clip.master.logging

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** 目录只查一级元数据，权限/枚举异常不能伪装成无日志。 */
class LogDirectoryMetadataReaderTest {
    /** 合成临时目录与用户 Download 无关。 */
    @get:Rule val temporary = TemporaryFolder()

    /** 当前、编号和旧会话计数，无关文件和目录不会膨胀数量。 */
    @Test fun countsOnlyRecognizedRootFiles() {
        /** 所有内容只为合成测试，不读取日志正文。 */
        val root = temporary.newFolder("logs")
        File(root, "clipmaster.txt").createNewFile()
        File(root, "clipmaster.txt.20").createNewFile()
        File(root, "clipmaster-synthetic-0001.txt").createNewFile()
        File(root, "notes.txt").createNewFile()
        File(root, "clipmaster.txt.01").mkdir()
        assertEquals(3, LogDirectoryMetadataReader.count(root))
        assertEquals(0, LogDirectoryMetadataReader.count(File(root, "missing")))
    }

    /** 无权枚举和目标被替换成普通文件都应保持错误语义。 */
    @Test fun unreadableOrInvalidDirectoryNeverReportsZero() {
        /** 可预测的失败桩，不依赖当前 Windows 管理员权限。 */
        val unreadable = object : File("synthetic") {
            /** 目录存在但列表被平台拒绝。 */
            override fun exists() = true
            /** 保持目录类型，让测试只覆盖枚举失败。 */
            override fun isDirectory() = true
            /** 模拟 Android 文件访问失败的 null 契约。 */
            override fun listFiles(): Array<File>? = null
        }
        assertThrows(IOException::class.java) { LogDirectoryMetadataReader.count(unreadable) }
        assertThrows(IOException::class.java) { LogDirectoryMetadataReader.count(temporary.newFile("invalid")) }
    }

    /** 重装不能自动冒领旧轮转文件，旧会话保留但不挡新固定文件族。 */
    @Test fun managedFilesRequireCurrentInstallationOwnership() {
        /** 固定基础文件触发归属检查。 */
        val root = temporary.newFolder("owned")
        File(root, "clipmaster.txt").createNewFile()
        assertThrows(IOException::class.java) { LogDirectoryMetadataReader.validateManagedFiles(root, false) }
        LogDirectoryMetadataReader.validateManagedFiles(root, true)
        /** 历史逐会话日志没有新轮转删除授权。 */
        val legacy = temporary.newFolder("legacy")
        File(legacy, "clipmaster-synthetic-0001.txt").createNewFile()
        LogDirectoryMetadataReader.validateManagedFiles(legacy, false)
    }
}
