package com.cla.clip.master.ad

/** 仅保存当前内存里的业务 key 顺序与像素偏移，不持久化也不向 SDK 或日志传递。 */
internal data class AdListAnchor(
    /** 广告布局变化前的真实内容顺序，用于原锚点删除后的最近邻降级。 */
    val contentKeys: List<Any>,
    /** 首个可见真实内容在原顺序中的位置。 */
    val contentIndex: Int,
    /** 该内容距视口顶端的反向像素偏移，可为负以保留顶部留白。 */
    val offset: Int,
)

/** 锚点在最新内容顺序中的恢复目标；不包含广告索引。 */
internal data class AdListAnchorTarget(
    /** 最新真实内容索引，调用方负责映射到 Lazy 布局索引。 */
    val index: Int,
    /** 需要恢复的原始像素偏移。 */
    val offset: Int,
)

/** 广告插入移除时的业务内容锚点规则；失去所有原内容时回到新内容起点，空列表返回 null。 */
internal object AdListAnchorPolicy {
    /** 优先原内容，其次原顺序中最近的后项，最后才尝试前项。 */
    fun resolve(anchor: AdListAnchor, currentKeys: List<Any>): AdListAnchorTarget? {
        if (anchor.contentIndex !in anchor.contentKeys.indices) return null
        // 新快照索引用于避免反复扫描；key 唯一性由业务列表已有去重保证。
        val currentIndices = currentKeys.withIndex().associate { it.value to it.index }
        // 原锚点及后方幸存内容优先，避免删除后回跳到已经读过的内容。
        val nextIndices = anchor.contentIndex until anchor.contentKeys.size
        for (index in nextIndices) {
            // 找到最近幸存 key 就返回，保持原屏幕像素位置。
            val target = currentIndices[anchor.contentKeys[index]]
            if (target != null) return AdListAnchorTarget(target, anchor.offset)
        }
        for (index in anchor.contentIndex - 1 downTo 0) {
            // 后方全部消失时向前降级，不选择完全无关的新记录。
            val target = currentIndices[anchor.contentKeys[index]]
            if (target != null) return AdListAnchorTarget(target, anchor.offset)
        }
        // 已进入全新数据代际且没有任何旧内容幸存时，回到起点而非沿用无意义的旧偏移。
        return currentKeys.takeIf { it.isNotEmpty() }?.let { AdListAnchorTarget(0, 0) }
    }
}
