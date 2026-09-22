package com.cla.clip.master.ad

/** 页面驻留预算；请求历史和内存驻留分开，上限不限制累计广告位置。 */
internal class AdPageLeasePolicy(
    /** 单个页面允许同时持有的对象数；调用方必须提供正数。 */
    private val capacity: Int = 3,
) {
    /** 单个驻留位置的可见状态及最近一次离屏顺序，仅保存在页面内存。 */
    private data class ResidentLease(
        /** 正在视口内的真实广告受到淘汰保护。 */
        val visible: Boolean,
        /** 离开视口的单调顺序；不可见且序号最小的位置最先被淘汰。 */
        val offscreenSequence: Long,
    )

    /** 本会话已经真正取得请求资格的位置，释放后仍保留以阻止重试。 */
    private val attempted = mutableSetOf<String>()
    /** 驻留记录只保存低敏位置身份，不保存广告对象或用户内容。 */
    private val resident = linkedMapOf<String, ResidentLease>()
    /** 单调事件序号代替真实时钟，使相同帧离屏也有确定顺序且便于单测。 */
    private var nextOffscreenSequence = 0L
    /** 只读快照，避免调用方修改账本。 */
    val residentKeys: Set<String> get() = resident.keys.toSet()

    init {
        // 零容量不能容纳请求，立即拒绝错误配置，避免隐藏无限增长行为。
        require(capacity > 0) { "广告驻留容量必须为正数" }
    }

    /** 首次位置获取租约；满额时只淘汰最久离屏位置，全部可见则等待容量。 */
    fun acquire(key: String): Boolean {
        if (key in attempted) return false
        if (resident.size >= capacity) {
            /** 仅离屏位置参与淘汰；没有候选时不记录 attempted，宿主可在可见性变化后重试。 */
            val evicted = resident.entries
                .filter { !it.value.visible }
                .minByOrNull { it.value.offscreenSequence }
                ?.key ?: return false
            resident.remove(evicted)
        }
        // 只有真正获得容量后才记录尝试，延后请求不会消耗一次性机会。
        attempted.add(key)
        // 加载但未显示的首位广告也算离屏，后续可见回调再提升保护。
        resident[key] = ResidentLease(visible = false, offscreenSequence = nextOffscreenSequence++)
        return true
    }

    /** 更新真实广告可见性，返回是否实际变化；重复回调不刷新离屏年龄。 */
    fun updateVisibility(key: String, visible: Boolean): Boolean {
        /** 未取得租约或已释放的位置不能通过布局回调复活。 */
        val previous = resident[key] ?: return false
        if (previous.visible == visible) return false
        resident[key] = previous.copy(
            visible = visible,
            offscreenSequence = if (visible) previous.offscreenSequence else nextOffscreenSequence++,
        )
        return true
    }

    /** 移除驻留位置，保留请求历史；迟到可见回调将被忽略。 */
    fun release(key: String) { resident.remove(key) }

    /** 页面关闭只释放持有对象，保留当前会话的请求历史。 */
    fun clear() { resident.clear() }
}
