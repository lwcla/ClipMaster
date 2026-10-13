package com.cla.clip.master.logging

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch

/** 进程桩验证取消和原生 stderr 失败，不依赖 Android/真实时间或实际日志。 */
class LogcatProcessSessionTest {
    /** 正常退出保留退出码，清理子进程和三个流。 */
    @Test fun exitKeepsCodeAndReleasesProcess() = runBlocking {
        /** 已结束的合成进程不需要真实 OS 子进程。 */
        val process = FakeProcess(ByteArrayInputStream(byteArrayOf()), finished = true)
        assertEquals(42, LogcatProcessSession.await(process).exitCode)
        assertTrue(process.destroyed)
    }

    /** rename 报错立即停止原生写入，只传播固定分类。 */
    @Test fun nativeRotationFailureStopsProcessAndKeepsStableReason() = runBlocking {
        /** 原始报错含合成路径，结果不得包含原文。 */
        val process = FakeProcess(ByteArrayInputStream("rename('synthetic-private', 'target') failed\n".toByteArray()))
        /** stderr 让进程停止并解开 waitFor。 */
        val result = withTimeout(5000) { LogcatProcessSession.await(process) }
        assertEquals("log_rotation_failed", result.reason)
        assertTrue(process.destroyed)
    }

    /** 关闭开关时必须释放阻塞读 stderr，不能等待永远不退出的旧会话。 */
    @Test fun cancellationDestroysProcessAndUnblocksErrorReader() = runBlocking {
        /** stderr 没有日志且没有 EOF，模拟正在运行的原生 logcat。 */
        val errors = BlockingErrorStream()
        /** 子进程一直等待销毁，取消必须使其结束。 */
        val process = FakeProcess(errors)
        /** 异步会话等待确定进入阻塞点，不靠 sleep 猜调度。 */
        val job = launch { LogcatProcessSession.await(process) }
        withTimeout(5000) {
            process.waiting.await()
            errors.reading.await()
            job.cancelAndJoin()
        }
        assertTrue(process.destroyed)
        assertTrue(errors.closed)
    }

    /** 只模拟 waitFor/destroy 契约，不启动系统进程或读业务日志。 */
    private class FakeProcess(
        /** 合成 stderr 用于失败与阻塞场景。 */ private val errors: InputStream,
        /** 首个测试可明确声明进程已结束。 */ finished: Boolean = false,
    ) : Process() {
        /** waitFor 可观察的进入信号。 */ val waiting = CompletableDeferred<Unit>()
        /** 销毁后的状态是测试关心的业务契约。 */ @Volatile var destroyed = false
        /** 不依赖时钟，通过销毁信号决定原生退出。 */ private val exited = CountDownLatch(if (finished) 0 else 1)
        /** stdout 不参与任何业务正文转存。 */ private val output = ByteArrayInputStream(byteArrayOf())
        /** stdin 只用于确保同一资源清理边界。 */ private val input = ByteArrayOutputStream()
        /** 模拟原生标准错误流。 */ override fun getErrorStream() = errors
        /** 模拟不用读取的标准输出流。 */ override fun getInputStream() = output
        /** 模拟不用写入的标准输入流。 */ override fun getOutputStream() = input
        /** 阻塞等待由取消/销毁结束，interrupt 对应 runInterruptible。 */
        override fun waitFor(): Int { waiting.complete(Unit); exited.await(); return 42 }
        /** 已退出时可读取固定合成退出码。 */ override fun exitValue(): Int = if (exited.count == 0L) 42 else throw IllegalThreadStateException()
        /** 幂等销毁使等待线程释放，错误流仍需要会话显式关闭。 */
        override fun destroy() { destroyed = true; exited.countDown() }
    }

    /** 可确定性阻塞的 stderr，close 必须在等待协程退出前执行。 */
    private class BlockingErrorStream : InputStream() {
        /** 读线程进入后再发取消，避免测试只覆盖尚未启动的场景。 */ val reading = CompletableDeferred<Unit>()
        /** 显式 close 的观察证据。 */ @Volatile var closed = false
        /** 模拟不能仅用协程取消中断的原生 IO。 */ private val released = CountDownLatch(1)
        /** 未关闭时阻塞，关闭后 EOF。 */ override fun read(): Int { reading.complete(Unit); released.await(); return -1 }
        /** 关闭解除阻塞，并记录释放结果。 */ override fun close() { closed = true; released.countDown() }
    }
}
