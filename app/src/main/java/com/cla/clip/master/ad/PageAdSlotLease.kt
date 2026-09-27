package com.cla.clip.master.ad

import com.cla.clip.feature.ad.api.AdSlotResourceOwner
import java.util.UUID

/** 单槽的一次资源生命期，缓存命中复用；淘汰后创建新的随机身份。 */
internal class PageAdSlotLease(
    /** 来源切换不能把另一个 SDK 的对象当成同一缓存。 */
    val sourceId: String,
) {
    /** 仅用于 adapter 内存身份，不参与日志或用户数据映射。 */
    val nonce: String = UUID.randomUUID().toString()
    /** 资源留在 SDK 内，页面只持有可关闭的所有者。 */
    val resources = AdSlotResourceOwner()
    /** 当前 Lazy 组合身份；与资源身份分开，防止同缓存的新旧挂载交接互相干扰。 */
    var binding: Any? = null
}
