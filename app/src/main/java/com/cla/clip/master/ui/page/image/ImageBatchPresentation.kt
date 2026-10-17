package com.cla.clip.master.ui.page.image

import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.base.general.dao.ImageExtractItemData

/** 两路 Room 读取都返回后的快照；batch 为空表示缺失，快照本身为空才表示加载。 */
internal data class ImageBatchSnapshot(
    /** 当前批次汇总，删除后的 null 不能当作永远加载。 */
    val batch: ImageExtractBatchData?,
    /** 当前批次全部单项状态，用于下载/发布阶段推导。 */
    val items: List<ImageExtractItemData>,
    /** 数据源异常与查无记录分开；不携带原始异常或用户内容。 */
    val readFailed: Boolean = false,
)

/** 只用于内存 UI，不作为数据库或备份协议。 */
internal enum class ImageBatchPhase { Loading, ReadError, Missing, Queued, Downloading, Publishing, Result }

/** 主进度的展示数据；processed 表示当前阶段已处理，不等同成功保存数量。 */
internal data class ImageBatchPresentation(
    /** 当前可行动阶段。 */
    val phase: ImageBatchPhase,
    /** 当前阶段已经处理的条目数。 */
    val processed: Int = 0,
    /** 当前批次总数。 */
    val total: Int = 0,
)

/** 从现有 item 状态推导阶段，避免新增临时数据库字段或改变备份协议。 */
internal fun presentImageBatch(snapshot: ImageBatchSnapshot?): ImageBatchPresentation {
    if (snapshot == null) return ImageBatchPresentation(ImageBatchPhase.Loading)
    if (snapshot.readFailed) return ImageBatchPresentation(ImageBatchPhase.ReadError)
    // 批次读取返回后缺失就是明确终态，不继续显示转圈。
    val batch = snapshot.batch ?: return ImageBatchPresentation(ImageBatchPhase.Missing)
    if (batch.status == ImageExtractBatchData.STATUS_EXTRACTED) return ImageBatchPresentation(ImageBatchPhase.Queued, total = batch.totalCount)
    if (batch.status != ImageExtractBatchData.STATUS_DOWNLOADING) return ImageBatchPresentation(ImageBatchPhase.Result, total = batch.totalCount)
    // Room 两路流短暂不同步时保留下载阶段，不把尚未拿到的 item 当成完成。
    val items = snapshot.items
    val downloading = items.size < batch.totalCount || items.any { it.status == ImageExtractItemData.STATUS_PENDING }
    // 网络阶段 temp_ready 已处理；发布阶段只有成功/失败/过滤才处理完毕。
    val processed = items.count {
        if (downloading) it.status != ImageExtractItemData.STATUS_PENDING
        else it.status in setOf(ImageExtractItemData.STATUS_SUCCESS, ImageExtractItemData.STATUS_FAILED, ImageExtractItemData.STATUS_FILTERED)
    }
    return ImageBatchPresentation(if (downloading) ImageBatchPhase.Downloading else ImageBatchPhase.Publishing,
        processed.coerceIn(0, batch.totalCount.coerceAtLeast(0)), batch.totalCount)
}
