package com.cla.clip.feature.torrent.search.data.document

import android.content.Context
import android.net.Uri
import com.cla.clip.feature.torrent.search.data.importer.BookmarkImportFailureReason
import com.cla.clip.feature.torrent.search.data.importer.BookmarkImportResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/** 一次性 SAF 文档 IO 契约；URI 只作为方法参数且从不持久化。 */
internal interface BookmarkDocumentGateway {
    /** 打开用户刚选择的输入文档并复用 05C 有界解析器。 */
    suspend fun read(uri: Uri): BookmarkImportResult

    /** 完整写出已通过 10 MiB 预检的 JSON；失败时抛固定 IO 边界异常。 */
    suspend fun write(uri: Uri, bytes: ByteArray)
}

/** Android ContentResolver 实现；所有阻塞流操作固定切换到 IO dispatcher。 */
internal class AndroidBookmarkDocumentGateway @Inject constructor(
    /** 应用 Context 只用于本次打开 ContentResolver 流，不保留 URI。 */
    @param:ApplicationContext private val context: Context,
) : BookmarkDocumentGateway {
    /** streamIo 把协程取消主动连接到实际 ContentResolver 流的 close。 */
    private val streamIo = CancellableBookmarkStreamIo()

    /** 读取并在关闭流后立即释放 URI 引用。 */
    override suspend fun read(uri: Uri): BookmarkImportResult {
        return try {
            streamIo.read { context.contentResolver.openInputStream(uri) }
        /** cancellation 必须穿透 IO 网关。 */
        } catch (cancellation: CancellationException) {
            throw cancellation
        /** exception 统一收敛为解析器已有的低敏读取失败。 */
        } catch (exception: Exception) {
            BookmarkImportResult.Failure(BookmarkImportFailureReason.READ_FAILURE)
        }
    }

    /** 只在调用前已生成完整文档后打开输出流，避免超限时留下截断文件。 */
    override suspend fun write(uri: Uri, bytes: ByteArray) {
        streamIo.write({ context.contentResolver.openOutputStream(uri, "wt") }, bytes)
    }
}
