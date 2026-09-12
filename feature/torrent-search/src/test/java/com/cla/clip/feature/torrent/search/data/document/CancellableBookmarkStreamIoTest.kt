package com.cla.clip.feature.torrent.search.data.document

import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SAF 实际流边界的阻塞取消、部分写入与主动关闭回归。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CancellableBookmarkStreamIoTest {
    /** 正常分块写完成后必须关闭输出流并保留全部字节。 */
    @Test
    fun successfulWriteClosesOutputAfterAllChunks() = runTest {
        /** executor 执行真实阻塞写边界。 */
        val executor = Executors.newSingleThreadExecutor()
        /** dispatcher 是可替换的后台执行器。 */
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            /** output 记录完整字节和关闭状态。 */
            val output = RecordingOutputStream()
            /** io 用小块稳定覆盖多轮写出。 */
            val io = CancellableBookmarkStreamIo(dispatcher, chunkSize = 4)

            io.write({ output }, "abcdefghij".toByteArray())

            assertEquals("abcdefghij", output.bytes.toString(Charsets.UTF_8))
            assertTrue(output.isClosed)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }
    /** 阻塞读取收到取消时必须由取消 hook 主动关闭底层输入流。 */
    @Test
    fun cancellingBlockedReadClosesInputStream() = runTest {
        /** executor 隔离真实阻塞读取，避免占用测试调度线程。 */
        val executor = Executors.newSingleThreadExecutor()
        /** dispatcher 是可替换的实际阻塞 IO 执行器。 */
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            /** input 会一直阻塞到 close 主动解除。 */
            val input = BlockingInputStream()
            /** io 是生产使用的可取消流传输边界。 */
            val io = CancellableBookmarkStreamIo(dispatcher, chunkSize = 4)
            /** job 执行真实解析读取路径。 */
            val job = launch { io.read { input } }
            runCurrent()
            assertTrue(input.readStarted.await(2, TimeUnit.SECONDS))

            job.cancelAndJoin()

            assertTrue(input.closed.await(2, TimeUnit.SECONDS))
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    /** 部分写入后取消必须关闭输出流，且不得继续写剩余字节。 */
    @Test
    fun cancellingPartialWriteClosesOutputAndStopsRemainingChunks() = runTest {
        /** executor 隔离真实阻塞写入。 */
        val executor = Executors.newSingleThreadExecutor()
        /** dispatcher 是可替换的实际阻塞 IO 执行器。 */
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            /** output 在首块后阻塞，模拟外部 Provider 部分落盘。 */
            val output = BlockingOutputStream()
            /** io 使用四字节分块以稳定触发部分写入。 */
            val io = CancellableBookmarkStreamIo(dispatcher, chunkSize = 4)
            /** bytes 包含三块，取消后只能观察到首块。 */
            val bytes = "abcdefghijkl".toByteArray()
            /** job 执行真实分块写路径。 */
            val job = launch { io.write({ output }, bytes) }
            runCurrent()
            assertTrue(output.firstChunkWritten.await(2, TimeUnit.SECONDS))

            job.cancelAndJoin()

            assertTrue(output.closed.await(2, TimeUnit.SECONDS))
            assertEquals(4, output.writtenBytes)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }
}

/** 记录成功写入及关闭状态的合成输出流。 */
private class RecordingOutputStream : OutputStream() {
    /** bytes 保存所有分块写入内容。 */
    val bytes = ByteArrayOutputStream()
    /** isClosed 标记成功路径已关闭。 */
    var isClosed = false

    /** 记录单字节写入。 */
    override fun write(value: Int) {
        bytes.write(value)
    }

    /** 记录一个完整分块。 */
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        bytes.write(buffer, offset, length)
    }

    /** 标记关闭。 */
    override fun close() {
        isClosed = true
    }
}

/** close 前永久阻塞的合成输入流。 */
private class BlockingInputStream : InputStream() {
    /** readStarted 标记后台线程已进入阻塞 read。 */
    val readStarted = CountDownLatch(1)
    /** closed 标记取消 hook 已主动关闭流。 */
    val closed = CountDownLatch(1)

    /** 等待 close 后以 EOF 结束阻塞。 */
    override fun read(): Int {
        readStarted.countDown()
        closed.await()
        return -1
    }

    /** 解除阻塞读取。 */
    override fun close() {
        closed.countDown()
    }
}

/** 首块写完后等待 close 的合成输出流。 */
private class BlockingOutputStream : OutputStream() {
    /** firstChunkWritten 标记边界已经产生外部部分副作用。 */
    val firstChunkWritten = CountDownLatch(1)
    /** closed 标记取消 hook 已主动关闭流。 */
    val closed = CountDownLatch(1)
    /** writtenBytes 记录取消前实际写入数量。 */
    @Volatile var writtenBytes = 0

    /** 单字节入口不应被分块实现调用。 */
    override fun write(value: Int) {
        writtenBytes += 1
    }

    /** 首块计数后阻塞，关闭时不再接收后续块。 */
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        writtenBytes += length
        firstChunkWritten.countDown()
        closed.await()
    }

    /** 解除阻塞写入。 */
    override fun close() {
        closed.countDown()
    }
}
