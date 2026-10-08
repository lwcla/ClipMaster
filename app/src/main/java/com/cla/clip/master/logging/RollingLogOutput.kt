package com.cla.clip.master.logging

import java.io.Closeable
import java.io.OutputStream
import java.io.IOException

/** 单线程会话日志分卷；IO 异常交给录制线程终止录制，不进入业务流程。 */
internal class RollingLogOutput(
    /** 每卷字节预算，单个完整长行允许超过预算。 */
    private val maxBytes: Long,
    /** 由 Android 平台提供各卷输出流，纯逻辑不持有 Context。 */
    private val openSegment: (Int) -> OutputStream,
) : Closeable {
    /** 当前已打开卷；没有记录时不创建空文件。 */
    private var output: OutputStream? = null
    /** 当前卷号，只递增，不覆盖历史卷。 */
    private var index = 0
    /** 当前卷真实 UTF-8 字节数，包含换行。 */
    private var written = 0L
    /** 生命周期结束后禁止重新打开文件。 */
    private var closed = false

    init {
        // 无效阈值在构造时拒绝，不能形成无限分卷。
        require(maxBytes > 0)
    }

    /** 每条记录完整保存并 flush；超长行独占一卷，禁止截断正文。 */
    fun appendLine(line: String) {
        if (closed) throw IOException("log_output_closed")
        /** 编码后计字节，中文与异常堆栈保持原样。 */
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        if (output != null && written > 0 && bytes.size.toLong() > maxBytes - written) {
            output?.close()
            output = null
            index += 1
            written = 0
        }
        /** 新卷只在本条确实需要写入时创建。 */
        val current = output ?: openSegment(index).also { output = it }
        current.write(bytes)
        current.flush()
        written += bytes.size
    }
    /** 停止时释放当前卷，不删除历史。 */
    override fun close() {
        if (closed) return
        closed = true
        /** 先转移所有权，再关闭；即使关闭失败也不能复活对象。 */
        val current = output
        output = null
        current?.close()
    }
}
