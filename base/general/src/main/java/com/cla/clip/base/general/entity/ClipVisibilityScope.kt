package com.cla.clip.base.general.entity

import kotlinx.serialization.Serializable

/**
 * 剪贴记录在列表和搜索中的可见范围。
 *
 * 使用明确枚举而不是布尔参数，是为了让列表页、折叠页和搜索页在跨层传递查询范围时保持语义清晰；
 * 后续如果增加“全部”或“回收站”等范围，也可以在这里扩展统一契约。
 */
@Serializable
enum class ClipVisibilityScope {
    /** 普通可见数据，只查询未折叠记录，供首页列表和普通搜索使用。 */
    VisibleOnly,

    /** 折叠数据，只查询已折叠记录，供折叠列表和折叠搜索使用。 */
    FoldedOnly
}
