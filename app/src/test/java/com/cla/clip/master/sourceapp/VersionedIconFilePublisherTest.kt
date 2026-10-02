package com.cla.clip.master.sourceapp

import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/** 真实临时目录验证版本发布与清理边界，图标内容用短合成字节替代 PNG。 */
class VersionedIconFilePublisherTest {
    /** 同内容重建必须得到不同路径，且旧文件仍可读取。 */
    @Test
    fun identicalContentPublishesNewPathWithoutReplacingPreviousFile() = withDirectory { directory ->
        /** 同目录的实际发布器。 */
        val publisher = VersionedIconFilePublisher(directory)
        /** 两次相同内容分别发布，路径是刷新身份而不是内容 hash。 */
        val first = publisher.publish("fixture.app") { it.write(byteArrayOf(1, 2)); true }!!
        val second = publisher.publish("fixture.app") { it.write(byteArrayOf(1, 2)); true }!!
        assertNotEquals(first.absolutePath, second.absolutePath)
        assertTrue(first.readBytes().contentEquals(second.readBytes()))
        assertEquals(2, directory.listFiles()!!.size)
    }

    /** 编码失败或抛异常不留下半文件，不会清空以前成功的版本。 */
    @Test
    fun failedEncodingLeavesNoHalfFileAndProtectsExistingFile() = withDirectory { directory ->
        /** 发布器和旧成功版本只位于测试目录。 */
        val publisher = VersionedIconFilePublisher(directory)
        val existing = publisher.publish("fixture") { it.write(1); true }!!
        assertEquals(null, publisher.publish("fixture") { it.write(2); false })
        assertThrows(IOException::class.java) { publisher.publish("fixture") { throw IOException("fixture") } }
        assertTrue(existing.exists())
        assertEquals(1, directory.listFiles()!!.size)
    }

    /** 路径穿越身份应在创建任何文件前拒绝。 */
    @Test
    fun unsafeIdentityIsRejectedBeforeWriting() = withDirectory { directory ->
        assertThrows(IllegalArgumentException::class.java) {
            VersionedIconFilePublisher(directory).publish("../outside") { it.write(1); true }
        }
        assertEquals(0, directory.listFiles()!!.size)
    }

    /** 清理只删除过宽限的未引用版本；正式引用、旧稳定文件和目录都保留。 */
    @Test
    fun cleanupKeepsReferencesFreshFilesAndLegacyStableNames() = withDirectory { directory ->
        /** 合成当前时间与旧版本年龄。 */
        val now = 200_000_000L
        val old = now - 2 * 24 * 60 * 60 * 1000L
        /** 一个过期孤儿与一个仍被数据库引用的过期正式版本。 */
        val orphan = File(directory, "fixture_1.png").apply { writeText("fixture"); setLastModified(old) }
        val referenced = File(directory, "fixture_2.png").apply { writeText("fixture"); setLastModified(old) }
        /** 宽限期中的新文件与旧实现的稳定文件名。 */
        val fresh = File(directory, "fixture_3.tmp").apply { writeText("fixture"); setLastModified(now) }
        val legacy = File(directory, "fixture.png").apply { writeText("fixture"); setLastModified(old) }
        assertEquals(1, VersionedIconFilePublisher(directory).cleanup(setOf(referenced.absolutePath), now))
        assertFalse(orphan.exists())
        assertTrue(referenced.exists() && fresh.exists() && legacy.exists())
    }

    /** 清理仅作用于本测试创建的已解析目录，避免触碰仓库或设备文件。 */
    private fun withDirectory(block: (File) -> Unit) {
        /** 运行时临时目录是唯一递归清理目标。 */
        val directory = createTempDirectory("source-icon-fixture").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
