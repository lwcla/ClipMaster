package com.cla.clip.master.ad

import androidx.compose.runtime.*
import com.cla.clip.feature.ad.api.AdReleaseReason
import java.util.UUID

/** 页面会话持有有限资源所有者、交互与驻留账本，不保存业务实体或 SDK 类型。 */
@Stable
internal class PageAdSession(
    /** 页面处于可保留资源的可见状态，新增请求另由 canRequest 控制。 */
    active: Boolean,
    /** 请求账本随业务 key 更换，用户关闭记录仍属于同一次页面访问。 */
    private val visitState: PageAdVisitState = PageAdVisitState(),
) {
    /** 当前访问的存活许可；真实离开后返回由所有者替换会话，短暂暂停保持原会话。 */
    var active by mutableStateOf(active)
        internal set
    /** 可保留资源但未恢复交互时，不允许新增请求和首次素材揭示。 */
    var canRequest by mutableStateOf(active)
        internal set
    /** 每个可见会话随机身份，不能反推剪贴或任务内容。 */
    val nonce: String = UUID.randomUUID().toString()
    /** 真实广告对象驻留和重复请求规则。 */
    private val leases = AdPageLeasePolicy()
    /** 每页最多三个无 SDK 类型的所有者，Lazy 解绑不移除此缓存。 */
    private val slots = mutableMapOf<String, PageAdSlotLease>()
    /** 驻留快照使被淘汰广告自动退出组合树。 */
    var residentKeys by mutableStateOf<Set<String>>(emptySet())
        private set
    /** 可见状态和驻留变化驱动尚未取得容量的槽重新尝试，不解除已请求位置保护。 */
    var leaseRevision by mutableIntStateOf(0)
        private set
    /** 由列表或页面显式提供的滚动/动画安全状态。 */
    var canMutateLayout by mutableStateOf(true)
    /** 临时遮挡只禁止请求、布局展开和点击，仍保留原素材。 */
    var suppressed by mutableStateOf(false)
    /** 手指按下期间禁止广告布局变更。 */
    var pointerPressed by mutableStateOf(false)
    /** 跨层页面可以提供实时指针读取，避免将按压快照逐层传递产生一帧延迟。 */
    var pointerPressedNow: () -> Boolean = { pointerPressed }
    /** SDK 发布视图之前同步保存页面锚点。 */
    var onLayoutWillChange: (() -> Unit)? = null
    /** SDK 发布视图之后请求下一帧恢复页面锚点。 */
    var onLayoutChanged: (() -> Unit)? = null

    /** 尝试取得位置租约；容量淘汰可在未来重新绑定，失败历史仍保留。 */
    fun acquire(key: String): Boolean {
        if (visitState.isDismissed(key)) return false
        // 成功取得新资源容量；已有对象的复用由 acquireSlot 提前处理。
        val acquired = leases.acquire(key)
        // 容量策略先完成，再同步销毁被淘汰的真实资源；不能只删除租约计数。
        val evicted = slots.keys.filter { it !in leases.residentKeys }
        evicted.forEach { evictedKey -> slots.remove(evictedKey)?.resources?.close(AdReleaseReason.Evicted) }
        residentKeys = leases.residentKeys
        if (acquired) leaseRevision++
        return acquired
    }
    /** 位置退出或失败后释放驻留计数，不允许同一身份重试。 */
    fun release(key: String) {
        // 没有驻留对象时不发布新版本，避免布局清理形成重组循环。
        val wasResident = key in residentKeys
        // 先移出所有权，SDK 的 Released 回调重入时不会误删新请求。
        val resource = slots.remove(key)
        leases.release(key)
        residentKeys = leases.residentKeys
        if (wasResident) leaseRevision++
        resource?.resources?.close(AdReleaseReason.LoadFailed)
    }
    /** 取得现存缓存或申请新资源；失败位置与没有容量时返回空。 */
    fun acquireSlot(key: String, sourceId: String, allowNewRequest: Boolean = true): PageAdSlotLease? {
        if (visitState.isDismissed(key)) return null
        // 命中当前来源时复用；切换来源必须释放旧对象。
        val cached = slots[key]
        if (cached != null && cached.sourceId == sourceId) return cached
        if (cached != null) releaseSlot(key, cached, AdReleaseReason.SourceUnavailable)
        // 暂停、临时遮挡和视口预取只允许复用，不能偷偷创建新网络工作。
        if (!active || !canRequest || suppressed || !allowNewRequest) return null
        if (!acquire(key)) return null
        return PageAdSlotLease(sourceId).also { slots[key] = it }
    }

    /** 校验绑定身份，旧组合回调只能影响它自己取得的资源。 */
    fun ownsSlot(key: String, slot: PageAdSlotLease): Boolean = slots[key] === slot

    /** 用户关闭按广告位识别，不依赖本次请求 nonce 或选中的 SDK 来源。 */
    fun isDismissed(key: String): Boolean = visitState.isDismissed(key)

    /** 只接受当前 View 绑定的关闭，旧回调不影响新绑定或其他广告位；成功返回 true。 */
    fun dismissSlot(key: String, slot: PageAdSlotLease, binding: Any): Boolean {
        if (!ownsSlot(key, slot) || slot.binding !== binding || visitState.isDismissed(key)) return false
        visitState.dismiss(key)
        releaseSlot(key, slot, AdReleaseReason.UserDismissed)
        return true
    }

    /** 同资源回绑也生成新的组合身份，新挂载接管可见性回报权限。 */
    fun bindSlot(key: String, slot: PageAdSlotLease, binding: Any) {
        if (ownsSlot(key, slot)) slot.binding = binding
    }

    /** Lazy 解绑仅标记离屏，旧组合不得撤销后来挂载的可见保护。 */
    fun detachSlot(key: String, slot: PageAdSlotLease, binding: Any? = slot.binding) {
        if (ownsSlot(key, slot) && slot.binding === binding) {
            slot.binding = null
            updateVisibility(key, false)
        }
    }

    /** 位置回报同样校验组合身份，防止延迟测量把新广告误标为离屏。 */
    fun updateSlotVisibility(key: String, slot: PageAdSlotLease, binding: Any, visible: Boolean) {
        if (ownsSlot(key, slot) && slot.binding === binding) updateVisibility(key, visible)
    }

    /** 释放指定生命期；只有非失败原因允许未来重新绑定时申请。 */
    fun releaseSlot(key: String, slot: PageAdSlotLease, reason: AdReleaseReason) {
        if (!ownsSlot(key, slot)) return
        slots.remove(key)
        leases.release(key, reason.retryOnNextBinding)
        residentKeys = leases.residentKeys
        leaseRevision++
        slot.resources.close(reason)
    }

    /** 来源/隐私关闭时连同离屏缓存一起释放，避免依赖 Lazy item 继续组合。 */
    fun retainSources(allowedSourceIds: Set<String>) {
        slots.toMap().forEach { (key, slot) ->
            if (slot.sourceId !in allowedSourceIds) releaseSlot(key, slot, AdReleaseReason.SourceUnavailable)
        }
    }
    /** View 实际进入/离开窗口时更新内存淘汰顺序，不输出任何业务坐标。 */
    fun updateVisibility(key: String, visible: Boolean) {
        if (leases.updateVisibility(key, visible)) leaseRevision++
    }
    /** 页面退出时释放全部租约，迟到回调由 adapter 自行销毁。 */
    fun clear(reason: AdReleaseReason = AdReleaseReason.PageLeft) {
        // 逐个清除资源所有权后再关闭 SDK，释放重入不会污染同名的新租约。
        slots.toMap().forEach { (key, slot) -> releaseSlot(key, slot, reason) }
        leases.clear()
        residentKeys = emptySet()
    }
}
