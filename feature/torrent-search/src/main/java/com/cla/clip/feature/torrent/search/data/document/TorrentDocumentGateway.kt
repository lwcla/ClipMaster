package com.cla.clip.feature.torrent.search.data.document

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** 一次性文档目标标记；ViewModel 无需依赖 Android URI 类型。 */
internal interface TorrentDocumentTarget

/** Android SAF 目标只在 Activity Result 回调和单次写出参数间传递。 */
internal class AndroidTorrentDocumentTarget(
    /** 用户刚选择且不得持久化的 URI。 */
    internal val uri: Uri,
) : TorrentDocumentTarget

/** 已校验 Torrent 字节的一次性 SAF 写出边界；实现不得持久化目标。 */
internal fun interface TorrentDocumentGateway {
    /** 把完整 Torrent 写入用户刚选择的文档，失败时抛出固定 IO 边界异常。 */
    suspend fun write(target: TorrentDocumentTarget, bytes: ByteArray)
}

/** 未装配测试的固定拒绝实现，防止默认构造意外写文件。 */
internal object NoOpTorrentDocumentGateway : TorrentDocumentGateway {
    /** 稳定拒绝未装配写出，不包含 URI 或用户内容。 */
    override suspend fun write(target: TorrentDocumentTarget, bytes: ByteArray): Unit = throw java.io.IOException("torrent_document_gateway_unavailable")
}

/** Android ContentResolver 实现；复用书签导入导出的可取消分块流能力。 */
internal class AndroidTorrentDocumentGateway @Inject constructor(
    /** 应用 Context 仅在本次调用内打开输出流，不保留用户 URI。 */
    @param:ApplicationContext private val context: Context,
) : TorrentDocumentGateway {
    /** streamIo 复用已有分块写出与取消主动 close 契约。 */
    private val streamIo = CancellableBookmarkStreamIo()

    /** 以覆盖模式完整写出，调用结束后由共享流能力关闭句柄。 */
    override suspend fun write(target: TorrentDocumentTarget, bytes: ByteArray) {
        /** androidTarget 是页面从本次 Activity Result 构造的 Android SAF 目标。 */
        val androidTarget = target as? AndroidTorrentDocumentTarget ?: throw java.io.IOException("torrent_document_target_invalid")
        streamIo.write({ context.contentResolver.openOutputStream(androidTarget.uri, "wt") }, bytes)
    }
}
