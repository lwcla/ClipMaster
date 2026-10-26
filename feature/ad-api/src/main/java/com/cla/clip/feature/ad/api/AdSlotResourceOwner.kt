package com.cla.clip.feature.ad.api

/** 封闭释放原因仅用于内存控制及低敏日志，不依赖枚举名称序列化。 */
enum class AdReleaseReason(
    /** 稳定诊断码，不包含页面业务身份。 */
    val code: String,
    /** 是否允许未来的新绑定重试；当前绑定不会因释放自动循环请求。 */
    val retryOnNextBinding: Boolean = true,
) {
    /** 页面退出组合或导航目的地结束。 */
    PageLeft("page_left"),
    /** 用户稳定选中了其他 Tab。 */
    TabChanged("tab_changed"),
    /** 页面已经不再可见，停止后台资源工作。 */
    LifecycleStop("lifecycle_stop"),
    /** 生命周期宿主永久销毁。 */
    Destroyed("destroyed"),
    /** 平台隐私许可撤销，优先清理所有缓存。 */
    PrivacyRevoked("privacy_revoked"),
    /** 页面有限容量淘汰离屏素材。 */
    Evicted("evicted"),
    /** 素材超过缓存有效期。 */
    Expired("expired"),
    /** 页面阶段切换或空间不足导致不再适用，与剪贴正文无关。 */
    Ineligible("ineligible"),
    /** 来源配置、开关或保险丝撤销当前来源。 */
    SourceUnavailable("source_unavailable"),
    /** 无填充、超时及网络失败不在同访问自动重试。 */
    LoadFailed("load_failed", false),
    /** 素材绑定失败后关闭，并交给来源保险丝处理。 */
    RenderFailed("render_failed", false),
    /** 用户关闭只影响本访问内的单个广告位，来源变化和回绑都不能自动重试。 */
    UserDismissed("user_dismissed", false),
}

/** SDK 私有资源只向宿主暴露释放能力，所有调用都由 UI 主线程串行执行。 */
fun interface AdSlotResource {
    /** 关闭资源并取消任务；实现必须拒绝迟到回调并支持重复关闭。 */
    fun close(reason: AdReleaseReason)
}

/** 单个请求的资源所有者；页面缓存持有本对象，Composable 解绑不会关闭它。 */
class AdSlotResourceOwner {
    /** 真实资源只由 adapter 创建，业务层不接触 SDK 类型。 */
    private var resource: AdSlotResource? = null
    /** 关闭是终态，防止迟到绑定重新持有 Activity。 */
    private var closed = false

    /** 同一所有者最多创建一次，已关闭返回空；工厂异常交由 adapter 处理。 */
    fun getOrCreate(factory: () -> AdSlotResource): AdSlotResource? {
        if (closed) return null
        return resource ?: factory().also { resource = it }
    }

    /** 先切换终态再回调 SDK，允许 Released 回调重入而不重复销毁。 */
    fun close(reason: AdReleaseReason) {
        if (closed) return
        closed = true
        // 先移出引用，释放过程中任何回调都无法再次获取旧资源。
        val previous = resource
        resource = null
        previous?.close(reason)
    }
}
