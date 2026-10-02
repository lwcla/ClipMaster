package com.cla.clip.master.ui.widget.sourceapp

import androidx.compose.runtime.Immutable
import com.cla.clip.base.general.dao.SourceAppData

/** 来源展示状态独立于 PagingData，空图标不丢失已经取得的有效名称。 */
@Immutable
internal sealed interface SourceAppPresentationState {
    /** 尚未读取元数据；UI 使用现有未知名称和默认图标。 */
    data object Loading : SourceAppPresentationState
    /** 来源不存在或无可用元数据，保留默认展示。 */
    data object Unavailable : SourceAppPresentationState
    /** 名称与图标元数据同一快照，按包名共享而非复制到每个分页记录。 */
    data class Ready(
        /** 持久来源记录，不持有图像对象。 */
        val source: SourceAppData,
    ) : SourceAppPresentationState
}
