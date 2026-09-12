package com.cla.clip.feature.torrent.search.security

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** 有界输入读取结果；调用方只获得完整字节、超限或低敏读取失败。 */
internal sealed interface BoundedInputReadResult {
    /** 输入在上限内完整读取。 */
    data class Success(
        /** 完整输入字节，仅在调用方的解析生命周期内持有。 */
        val bytes: ByteArray,
    ) : BoundedInputReadResult

    /** 输入超过调用方指定上限，读取器已立即停止。 */
    data object TooLarge : BoundedInputReadResult

    /** 底层 IO 失败；不携带可能含路径或 URI 的异常 message。 */
    data object ReadFailure : BoundedInputReadResult
}

/** 从不可信 InputStream 读取固定上限字节，且不负责关闭调用方拥有的流。 */
internal object BoundedInputReader {
    /** 固定 8 KiB 缓冲兼顾小文件与 10/20 MiB 上限，不信任 Content-Length。 */
    private const val BUFFER_SIZE = 8 * 1024

    /** 读取到 maxBytes 后再探测一个字节，超过上限立即返回 TooLarge。 */
    fun read(
        /** 由 SAF、网络或测试提供的输入流，生命周期仍由调用方管理。 */
        input: InputStream,
        /** 允许完整读取的最大字节数，必须非负。 */
        maxBytes: Int,
    ): BoundedInputReadResult {
        require(maxBytes >= 0)
        /** output 只缓存已确认不超过上限的输入。 */
        val output = ByteArrayOutputStream(minOf(maxBytes, BUFFER_SIZE))
        /** buffer 是每轮读取的固定临时块。 */
        val buffer = ByteArray(BUFFER_SIZE)
        /** totalBytes 记录已接受的累计字节数。 */
        var totalBytes = 0
        return try {
            while (true) {
                /** remainingWithProbe 最多请求剩余额度加一个探测字节。 */
                val remainingWithProbe = maxBytes - totalBytes + 1
                /** requestedBytes 避免单轮读取超过缓冲或剩余额度。 */
                val requestedBytes = minOf(buffer.size, remainingWithProbe)
                /** readBytes 为当前读取量；-1 表示流完整结束。 */
                val readBytes = input.read(buffer, 0, requestedBytes)
                if (readBytes == -1) break
                if (readBytes == 0) {
                    /** 单字节探测保证违反 InputStream 约定的零长度读取不会造成死循环。 */
                    val singleByte = input.read()
                    if (singleByte == -1) break
                    if (totalBytes == maxBytes) return BoundedInputReadResult.TooLarge
                    output.write(singleByte)
                    totalBytes += 1
                    continue
                }
                if (totalBytes + readBytes > maxBytes) return BoundedInputReadResult.TooLarge
                output.write(buffer, 0, readBytes)
                totalBytes += readBytes
            }
            BoundedInputReadResult.Success(output.toByteArray())
        /** IOException 只映射低敏失败类型，不向上泄漏路径或 URI。 */
        } catch (failure: IOException) {
            BoundedInputReadResult.ReadFailure
        }
    }
}
