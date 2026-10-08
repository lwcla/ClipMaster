package com.cla.clip.master.logging

import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** 用合成正文验证完整保留、分卷和资源释放，不读取真实应用日志。 */
class RollingLogOutputTest {
    /** 各级及多行堆栈不再受备份摘要条数限制。 */
    @Test fun preservesEveryLineAndLevel() {
        /** 内存卷模拟 Download 文件，逐行内容可直接观察。 */
        val files = mutableListOf<ByteArrayOutputStream>()
        RollingLogOutput(1024) { ByteArrayOutputStream().also(files::add) }.use { output ->
            listOf("V sample", "D sample", "I sample", "W sample", "E sample", "  at synthetic.frame").forEach(output::appendLine)
        }
        assertEquals("V sample\nD sample\nI sample\nW sample\nE sample\n  at synthetic.frame\n", files.joinToString("") { it.toString("UTF-8") })
    }

    /** 分卷按 UTF-8 字节，而非中文字符数计算。 */
    @Test fun rotatesByUtf8BytesWithoutSplittingLines() {
        /** 捕获每个内存卷及创建次序。 */
        val files = mutableListOf<ByteArrayOutputStream>()
        RollingLogOutput(8) { ByteArrayOutputStream().also(files::add) }.use { output ->
            output.appendLine("中文")
            output.appendLine("中文")
        }
        assertEquals(listOf("中文\n", "中文\n"), files.map { it.toString("UTF-8") })
    }

    /** 达到阈值时才在下一条分卷，不生成空卷。 */
    @Test fun exactLimitCreatesNoEmptyTrailingSegment() {
        /** 两个两字节行刚好占满一卷。 */
        val files = mutableListOf<ByteArrayOutputStream>()
        RollingLogOutput(4) { ByteArrayOutputStream().also(files::add) }.use { output ->
            output.appendLine("a")
            output.appendLine("b")
        }
        assertEquals(listOf("a\nb\n"), files.map { it.toString("UTF-8") })
    }

    /** 单行超过阈值不能被截断，下一条继续新卷。 */
    @Test fun keepsOversizedLineIntactAndThenRotates() {
        /** 大行仅为合成边界数据。 */
        val files = mutableListOf<ByteArrayOutputStream>()
        RollingLogOutput(4) { ByteArrayOutputStream().also(files::add) }.use { output ->
            output.appendLine("long-line")
            output.appendLine("a")
        }
        assertEquals(listOf("long-line\n", "a\n"), files.map { it.toString("UTF-8") })
    }

    /** 打不开公共文件时给录制器明确异常，不能伪造保存成功。 */
    @Test fun openFailureIsReported() {
        /** 只模拟系统存储故障，不依赖真实磁盘。 */
        val output = RollingLogOutput(4) { throw IOException("synthetic") }
        assertThrows(IOException::class.java) { output.appendLine("a") }
    }

    /** 转卷必须关闭上一卷，结束必须关闭当前卷。 */
    @Test fun closesAllSegmentsOnRotationAndCompletion() {
        /** 记录流关闭事件，不访问真实 MediaStore。 */
        val closed = mutableListOf<Int>()
        RollingLogOutput(2) { index ->
            object : ByteArrayOutputStream() {
                /** 每个流只上报自己所属卷。 */
                override fun close() { closed += index; super.close() }
            }
        }.use { output -> output.appendLine("a"); output.appendLine("b") }
        assertEquals(listOf(0, 1), closed)
    }

    /** 已关闭对象不能重新创建日志，防止生命周期结束后继续 IO。 */
    @Test fun rejectsWritingAfterClose() {
        /** 关闭前没有打开卷，关闭后也不能复活。 */
        val output = RollingLogOutput(4) { ByteArrayOutputStream() }
        output.close()
        assertThrows(IOException::class.java) { output.appendLine("a") }
    }

    /** 写入故障必须保持异常语义，use 仍释放已创建卷。 */
    @Test fun writeFailureStillClosesCurrentSegment() {
        /** 合成关闭状态用于确认异常不会泄漏输出流。 */
        var closed = false
        /** 只模拟写入失败，不包含真实日志正文。 */
        val stream = object : ByteArrayOutputStream() {
            /** 模拟系统写入异常，交给录制边界隔离。 */
            override fun write(bytes: ByteArray, offset: Int, length: Int) { throw IOException("synthetic") }
            /** 记录流已释放，不依赖 Android 文件句柄。 */
            override fun close() { closed = true; super.close() }
        }
        assertThrows(IOException::class.java) { RollingLogOutput(4) { stream }.use { it.appendLine("a") } }
        assertTrue(closed)
    }

    /** flush 故障同样终止本轮录制，不伪造成功或遗留流。 */
    @Test fun flushFailureStillClosesCurrentSegment() {
        /** 合成关闭状态与内存流独立，避免真实磁盘测试。 */
        var closed = false
        /** 内容已进入内存后模拟刷新失败。 */
        val stream = object : ByteArrayOutputStream() {
            /** 模拟平台输出故障，不泄露原始异常信息。 */
            override fun flush() { throw IOException("synthetic") }
            /** 刷新失败后仍必须释放当前卷。 */
            override fun close() { closed = true; super.close() }
        }
        assertThrows(IOException::class.java) { RollingLogOutput(4) { stream }.use { it.appendLine("a") } }
        assertTrue(closed)
    }

    /** 关闭本身失败也不能再次关闭或复活当前卷。 */
    @Test fun closeFailureIsReportedOnlyOnce() {
        /** 合成关闭次数确认所有权已转移，不触发重复清理。 */
        var closeCount = 0
        /** 模拟系统关闭失败，录制器会终止采集。 */
        val stream = object : ByteArrayOutputStream() {
            /** 失败仍记录唯一释放尝试，后续 close 不再触碰流。 */
            override fun close() { closeCount += 1; throw IOException("synthetic") }
        }
        /** 保存一条后再结束，确保实际打开了该卷。 */
        val output = RollingLogOutput(4) { stream }
        output.appendLine("a")
        assertThrows(IOException::class.java) { output.close() }
        output.close()
        assertEquals(1, closeCount)
    }
}
