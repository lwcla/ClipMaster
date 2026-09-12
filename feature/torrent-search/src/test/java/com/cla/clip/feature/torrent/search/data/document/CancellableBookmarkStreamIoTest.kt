package com.cla.clip.feature.torrent.search.data.document

import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SAF 实际流边界的阻塞取消、部分写入与主动关闭回归。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CancellableBookmarkStreamIoTest {
    /** Main 调用取消时不得同步等待阻塞 close，且关闭只能由独立后台线程执行一次。 */
    @Test
    fun cancellingPartialWriteReturnsBeforeBlockingCloseAndClosesExactlyOnceOffCaller() = runTest {
        /** operationExecutor 模拟已被阻塞 write 占用的唯一 IO 队列。 */
        val operationExecutor = Executors.newSingleThreadExecutor()
        /** operationDispatcher 承载实际写入，取消关闭不能继续排入此队列。 */
        val operationDispatcher = operationExecutor.asCoroutineDispatcher()
        /** closeExecutor 提供不被阻塞 write 占用的独立关闭队列。 */
        val closeExecutor = Executors.newSingleThreadExecutor()
        /** closeDispatcher 承载潜在阻塞的 Provider close。 */
        val closeDispatcher = closeExecutor.asCoroutineDispatcher()
        /** mainExecutor 模拟发出生命周期 STOP/cancel 的 Main 线程。 */
        val mainExecutor = Executors.newSingleThreadExecutor()
        try {
            /** output 在第一块写入后阻塞，并让 close 本身继续阻塞到测试放行。 */
            val output = BlockingCloseOutputStream()
            /** io 显式隔离写入和关闭 dispatcher，稳定验证队列不会互相饿死。 */
            val io = CancellableBookmarkStreamIo(operationDispatcher, closeDispatcher, chunkSize = 4)
            /** job 写入三块数据，取消后只能保留首块。 */
            val job = launch { io.write({ output }, "abcdefghijkl".toByteArray()) }
            runCurrent()
            assertTrue(output.firstChunkWritten.await(2, TimeUnit.SECONDS))
            /** cancelCallerThread 记录模拟 Main 的准确线程身份。 */
            val cancelCallerThread = AtomicReference<Thread?>()
            /** cancelFuture 在独立调用线程同步执行 Job.cancel。 */
            val cancelFuture = mainExecutor.submit {
                cancelCallerThread.set(Thread.currentThread())
                job.cancel()
            }
            assertTrue(output.closeStarted.await(2, TimeUnit.SECONDS))
            /** cancelReturnedBeforeCloseRelease 证明 Main 不会等待阻塞 close。 */
            val cancelReturnedBeforeCloseRelease = runCatching {
                cancelFuture.get(300, TimeUnit.MILLISECONDS)
                true
            }.getOrDefault(false)
            try {
                assertTrue(cancelReturnedBeforeCloseRelease)
                assertFalse(output.closeThread.get() === cancelCallerThread.get())
            } finally {
                output.releaseClose.countDown()
            }
            job.cancelAndJoin()

            assertEquals(4, output.writtenBytes)
            assertEquals(1, output.closeCount.get())
        } finally {
            operationDispatcher.close()
            closeDispatcher.close()
            operationExecutor.shutdownNow()
            closeExecutor.shutdownNow()
            mainExecutor.shutdownNow()
        }
    }

    /** 正常关闭异常必须作为写失败传播，且关闭只调用一次。 */
    @Test
    fun successfulWritePropagatesCloseFailureExactlyOnce() = runTest {
        /** operationExecutor 承载实际分块写入。 */
        val operationExecutor = Executors.newSingleThreadExecutor()
        /** operationDispatcher 是可替换写入调度器。 */
        val operationDispatcher = operationExecutor.asCoroutineDispatcher()
        /** closeExecutor 仅供取消关闭，本用例正常路径不会占用。 */
        val closeExecutor = Executors.newSingleThreadExecutor()
        /** closeDispatcher 保持构造契约与生产一致。 */
        val closeDispatcher = closeExecutor.asCoroutineDispatcher()
        try {
            /** output 在正常 close 时抛出固定合成 IO 异常。 */
            val output = CloseFailingOutputStream()
            /** io 使用独立关闭队列，正常路径仍需等待并传播 close 结果。 */
            val io = CancellableBookmarkStreamIo(operationDispatcher, closeDispatcher, chunkSize = 4)
            /** failure 捕获外部可观察的关闭失败。 */
            val failure = runCatching { io.write({ output }, "abcdefgh".toByteArray()) }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertEquals(1, output.closeCount.get())
        } finally {
            operationDispatcher.close()
            closeDispatcher.close()
            operationExecutor.shutdownNow()
            closeExecutor.shutdownNow()
        }
    }

    /** 部分写入抛异常时必须保留原始 IO 失败并且只关闭一次。 */
    @Test
    fun partialWriteFailureClosesExactlyOnce() = runTest {
        /** operationExecutor 承载实际分块写入。 */
        val operationExecutor = Executors.newSingleThreadExecutor()
        /** operationDispatcher 是可替换写入调度器。 */
        val operationDispatcher = operationExecutor.asCoroutineDispatcher()
        /** closeExecutor 隔离取消关闭队列。 */
        val closeExecutor = Executors.newSingleThreadExecutor()
        /** closeDispatcher 与写入队列不共享单线程。 */
        val closeDispatcher = closeExecutor.asCoroutineDispatcher()
        try {
            /** output 在第一块产生部分副作用后抛出固定异常。 */
            val output = PartialWriteFailingOutputStream()
            /** io 用四字节块固定失败发生位置。 */
            val io = CancellableBookmarkStreamIo(operationDispatcher, closeDispatcher, chunkSize = 4)
            /** failure 捕获外部可观察的原始写入异常。 */
            val failure = runCatching { io.write({ output }, "abcdefgh".toByteArray()) }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertEquals(4, output.writtenBytes)
            assertEquals(1, output.closeCount.get())
        } finally {
            operationDispatcher.close()
            closeDispatcher.close()
            operationExecutor.shutdownNow()
            closeExecutor.shutdownNow()
        }
    }

    /** 取消发生在打开返回前时，迟到的流不得读取并且必须在独立队列关闭一次。 */
    @Test
    fun cancellingDuringOpenClosesLateStreamExactlyOnceWithoutReading() = runTest {
        /** operationExecutor 承载会阻塞的 openInput。 */
        val operationExecutor = Executors.newSingleThreadExecutor()
        /** operationDispatcher 模拟唯一且当前被打开操作占用的 IO 队列。 */
        val operationDispatcher = operationExecutor.asCoroutineDispatcher()
        /** closeExecutor 提供独立关闭队列。 */
        val closeExecutor = Executors.newSingleThreadExecutor()
        /** closeDispatcher 确保迟到流不回到被占用的打开队列关闭。 */
        val closeDispatcher = closeExecutor.asCoroutineDispatcher()
        try {
            /** openStarted 标记后台线程已经进入不可由 close 打断的打开阶段。 */
            val openStarted = CountDownLatch(1)
            /** releaseOpen 由测试模拟外部 Provider 最终返回流。 */
            val releaseOpen = CountDownLatch(1)
            /** input 记录迟到流是否被错误读取或重复关闭。 */
            val input = LateOpenInputStream()
            /** io 显式隔离操作和关闭队列。 */
            val io = CancellableBookmarkStreamIo(operationDispatcher, closeDispatcher, chunkSize = 4)
            /** job 执行会卡在打开器中的读取。 */
            val job = launch {
                io.read {
                    openStarted.countDown()
                    releaseOpen.await()
                    input
                }
            }
            runCurrent()
            assertTrue(openStarted.await(2, TimeUnit.SECONDS))

            job.cancel()
            releaseOpen.countDown()
            assertTrue(input.closed.await(2, TimeUnit.SECONDS))
            job.cancelAndJoin()

            assertEquals(0, input.readCount.get())
            assertEquals(1, input.closeCount.get())
        } finally {
            operationDispatcher.close()
            closeDispatcher.close()
            operationExecutor.shutdownNow()
            closeExecutor.shutdownNow()
        }
    }

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

/** 首块写入与 close 都可分别阻塞的合成输出流。 */
private class BlockingCloseOutputStream : OutputStream() {
    /** firstChunkWritten 标记外部 Provider 已接受第一块。 */
    val firstChunkWritten = CountDownLatch(1)
    /** closeStarted 标记后台关闭线程已取得流所有权。 */
    val closeStarted = CountDownLatch(1)
    /** releaseClose 让测试在验证 cancel 返回后放行关闭。 */
    val releaseClose = CountDownLatch(1)
    /** writeRelease 在 close 完成后解除被占用的写入线程。 */
    private val writeRelease = CountDownLatch(1)
    /** closeCount 验证取消与正常 finally 不会重复关闭同一流。 */
    val closeCount = AtomicInteger(0)
    /** closeThread 记录潜在阻塞关闭所在的线程。 */
    val closeThread = AtomicReference<Thread?>()
    /** writtenBytes 记录取消前已产生的外部部分副作用。 */
    @Volatile var writtenBytes = 0

    /** 单字节入口不应被分块实现调用。 */
    override fun write(value: Int) {
        writtenBytes += 1
    }

    /** 第一块写入后等待关闭线程完成，确保操作队列始终被占用。 */
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        writtenBytes += length
        firstChunkWritten.countDown()
        writeRelease.await()
    }

    /** 记录关闭线程并阻塞，放行后再解除写入。 */
    override fun close() {
        closeCount.incrementAndGet()
        closeThread.set(Thread.currentThread())
        closeStarted.countDown()
        releaseClose.await()
        writeRelease.countDown()
    }
}

/** 正常关闭会失败的合成输出流。 */
private class CloseFailingOutputStream : OutputStream() {
    /** closeCount 验证失败关闭不会重试或重复执行。 */
    val closeCount = AtomicInteger(0)

    /** 接受全部测试字节。 */
    override fun write(value: Int) = Unit

    /** 抛出固定关闭异常供传播断言。 */
    override fun close() {
        closeCount.incrementAndGet()
        throw IOException("synthetic_close_failure")
    }
}

/** 第一块写入后立即失败的合成输出流。 */
private class PartialWriteFailingOutputStream : OutputStream() {
    /** writtenBytes 记录失败前实际接受的字节。 */
    var writtenBytes = 0
    /** closeCount 验证异常 finally 仅关闭一次。 */
    val closeCount = AtomicInteger(0)

    /** 单字节入口不应被分块实现调用。 */
    override fun write(value: Int) {
        throw IOException("synthetic_partial_write_failure")
    }

    /** 记录当前块后抛出固定部分写失败。 */
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        writtenBytes += length
        throw IOException("synthetic_partial_write_failure")
    }

    /** 记录异常路径关闭次数。 */
    override fun close() {
        closeCount.incrementAndGet()
    }
}

/** 打开阶段取消后才返回的合成输入流。 */
private class LateOpenInputStream : InputStream() {
    /** readCount 验证迟到流从未进入 parser。 */
    val readCount = AtomicInteger(0)
    /** closeCount 验证迟到流只关闭一次。 */
    val closeCount = AtomicInteger(0)
    /** closed 让测试等待后台关闭真正发生。 */
    val closed = CountDownLatch(1)

    /** 若被调用则记录错误读取并返回 EOF。 */
    override fun read(): Int {
        readCount.incrementAndGet()
        return -1
    }

    /** 记录迟到流关闭。 */
    override fun close() {
        closeCount.incrementAndGet()
        closed.countDown()
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
