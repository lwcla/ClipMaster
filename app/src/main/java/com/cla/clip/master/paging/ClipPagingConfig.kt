package com.cla.clip.master.paging

import androidx.paging.PagingConfig

/** 四类剪贴结果共享分页策略；保留已加载文本页，确保索引零始终表示最新记录。 */
internal fun clipPagingConfig(): PagingConfig = PagingConfig(
    // 每页二十条兼顾首屏填充与数据库读取成本，正文仍按需分页。
    pageSize = 20,
    // 距末尾五条时预取下一页，避免连续滚动出现空白。
    prefetchDistance = 5,
    // 没有稳定总数占位需求，关闭占位；默认无限已加载窗口保护已有回顶语义。
    enablePlaceholders = false,
)
