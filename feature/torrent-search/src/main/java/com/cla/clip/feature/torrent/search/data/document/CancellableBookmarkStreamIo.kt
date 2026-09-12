package com.cla.clip.feature.torrent.search.data.document

import com.cla.clip.feature.torrent.search.data.importer.BookmarkImportParser
import com.cla.clip.feature.torrent.search.data.importer.BookmarkImportResult
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine

/** 在可替换阻塞 dispatcher 上执行 SAF 分块 IO，并把取消主动连接到底层 close。 */
internal class CancellableBookmarkStreamIo(
    /** 真正执行 ContentResolver 流读取与写出的后台 dispatcher。 */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 写出分块大小；测试可缩小以稳定覆盖部分写入。 */
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) {
    /** 打开输入流后逐块读取并复用 05C parser；调用结束始终关闭流。 */
    suspend fun read(
        /** 延迟到后台任务内执行的一次性输入流打开器。 */
        openInput: () -> InputStream?,
    ): BookmarkImportResult = execute(openInput) { /** input 是已绑定取消关闭的底层输入流。 */ input, /** isActive 提供逐次读取的取消检查。 */ isActive ->
        BookmarkImportParser.parse(CancellationCheckingInputStream(input, isActive))
    }

    /** 打开输出流后分块写出；调用结束或取消始终关闭流。 */
    suspend fun write(
        /** 延迟到后台任务内执行的一次性输出流打开器。 */
        openOutput: () -> OutputStream?,
        /** 已在打开流前完成协议和大小校验的完整文档。 */
        bytes: ByteArray,
    ) {
        execute(openOutput) { /** output 是已绑定取消关闭的底层输出流。 */ output, /** isActive 提供每块写出前后的取消检查。 */ isActive ->
            /** offset 记录尚未写出的首字节下标。 */
            var offset = 0
            while (offset < bytes.size) {
                if (!isActive()) throw CancellationException("bookmark_document_write_cancelled")
                /** length 是当前不超过固定缓冲的写出量。 */
                val length = minOf(chunkSize, bytes.size - offset)
                output.write(bytes, offset, length)
                offset += length
            }
            if (!isActive()) throw CancellationException("bookmark_document_write_cancelled")
            output.flush()
        }
    }

    /** 在独立阻塞线程运行单个流操作；取消回调不等待该线程即可主动 close。 */
    private suspend fun <Stream : Closeable, Output> execute(
        /** 仅在后台线程执行的流打开器。 */
        openStream: () -> Stream?,
        /** 接收当前流和无阻塞取消探针的实际操作。 */
        operation: (Stream, () -> Boolean) -> Output,
    ): Output = suspendCancellableCoroutine { /** continuation 是调用方结构化取消与结果边界。 */ continuation ->
        /** activeStream 让任意取消线程准确关闭当前已打开流。 */
        val activeStream = AtomicReference<Stream?>(null)
        continuation.invokeOnCancellation {
            /** stream 是取消瞬间仍归本次操作所有的流。 */
            val stream = activeStream.getAndSet(null)
            runCatching { stream?.close() }
        }
        ioDispatcher.dispatch(EmptyCoroutineContext) {
            if (!continuation.isActive) return@dispatch
            /** stream 只属于当前后台操作，并由 finally 兜底关闭。 */
            val stream = try {
                openStream() ?: throw java.io.IOException("bookmark_document_open_failed")
            /** failure 是打开流阶段的固定边界异常，不包含在业务状态中持久化。 */
            } catch (failure: Throwable) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
                return@dispatch
            }
            activeStream.set(stream)
            if (!continuation.isActive) {
                activeStream.getAndSet(null)?.let { /** openedStream 是取消与打开竞态中刚取得的流。 */ openedStream -> runCatching { openedStream.close() } }
                return@dispatch
            }
            /** operationResult 保留实际读写成功或失败，关闭完成前不向调用方报告成功。 */
            val operationResult = runCatching { operation(stream) { continuation.isActive } }
            /** ownedStream 仅由成功取回引用的一方执行最终关闭。 */
            val ownedStream = activeStream.getAndSet(null)
            /** closeFailure 在正常路径中属于真实传输失败，不能静默报告成功。 */
            val closeFailure = runCatching { ownedStream?.close() }.exceptionOrNull()
            if (continuation.isActive) {
                /** finalResult 优先保留原始操作失败；仅操作成功时把关闭失败上抛。 */
                val finalResult = if (operationResult.isSuccess && closeFailure != null) {
                    Result.failure(closeFailure)
                } else {
                    operationResult
                }
                continuation.resumeWith(finalResult)
            }
        }
    }

    /** 生产写出分块固定 8 KiB。 */
    companion object {
        /** 在取消响应与 ContentProvider 吞吐之间保持小块边界。 */
        private const val DEFAULT_CHUNK_SIZE = 8 * 1024
    }
}

/** 每次读取前后检查取消，并依赖外层取消 hook 打断已阻塞的底层 read。 */
private class CancellationCheckingInputStream(
    /** 实际 ContentResolver 输入流。 */
    private val delegate: InputStream,
    /** true 表示调用方仍拥有本次读取。 */
    private val isActive: () -> Boolean,
) : InputStream() {
    /** 读取一个字节并在前后检查任务所有权。 */
    override fun read(): Int {
        ensureActive()
        /** value 是底层返回的单字节或 EOF。 */
        val value = delegate.read()
        ensureActive()
        return value
    }

    /** 读取一个分块并在前后检查任务所有权。 */
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        ensureActive()
        /** readBytes 是底层本轮返回量。 */
        val readBytes = delegate.read(buffer, offset, length)
        ensureActive()
        return readBytes
    }

    /** 取消后抛结构化 CancellationException，不能降级为普通读取失败。 */
    private fun ensureActive() {
        if (!isActive()) throw CancellationException("bookmark_document_read_cancelled")
    }
}
