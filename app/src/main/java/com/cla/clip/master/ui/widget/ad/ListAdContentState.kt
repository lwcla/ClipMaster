package com.cla.clip.master.ui.widget.ad

/** 仅表达页面当前实际画面；无实体、数量查询或历史非空标记，允许请求与内容独立启动。 */
internal enum class ListAdContentState {
    /** 正在展示加载占位，旧快照不能作为当前真实内容。 */
    Loading,
    /** 当前实际展示真实记录，广告素材仍须通过自己的来源及布局门。 */
    Rendered,
    /** 当前只有空态、无内容错误提示或搜索历史模式，广告呈现零占位。 */
    Empty;

    /** 内容资格持续生效；空态必须在同次组合撤下广告，而非延迟转发可见状态。 */
    val containsContent: Boolean get() = this == Rendered

    companion object {
        /** 加载画面优先，非加载且有真实渲染记录才允许广告呈现，不影响请求资格。 */
        fun from(hasRenderedItems: Boolean, showsLoading: Boolean): ListAdContentState = when {
            showsLoading -> Loading
            hasRenderedItems -> Rendered
            else -> Empty
        }
    }
}
