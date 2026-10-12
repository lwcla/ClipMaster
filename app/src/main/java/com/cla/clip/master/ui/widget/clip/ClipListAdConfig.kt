package com.cla.clip.master.ui.widget.clip

import com.cla.clip.feature.ad.api.AdPlacement
import com.cla.clip.master.ad.PageAdSession

/** 固定广告由共享列表或页面外层承载，防止搜索页同时建立两个宿主；仅供编译期 UI 接线。 */
internal enum class ClipListAdHost {
    /** 普通/折叠/回收站由共享列表在内容分支外建立固定宿主。 */
    Fixed,
    /** 搜索外层保持宿主跨 History/Result 存活，共享列表只保留交互协调。 */
    External,
}

/** 剪贴列表固定广告配置，只包含展示契约，不携带正文或混排位置规则。 */
internal data class ClipListAdConfig(
    /** 页面可见访问会话；查询、排序和分页追加保持同一访问。 */
    val session: PageAdSession,
    /** 已批准的固定列表广告位置。 */
    val placement: AdPlacement,
    /** 管理多选、弹窗或预览时禁止广告请求及交互。 */
    val suppressed: Boolean = false,
    /** 页面自有动画或确认操作期间不允许改变布局。 */
    val layoutBlocked: Boolean = false,
    /** 搜索由页面保持跨模式宿主，其余剪贴列表使用默认共享宿主。 */
    val host: ClipListAdHost = ClipListAdHost.Fixed,
)
