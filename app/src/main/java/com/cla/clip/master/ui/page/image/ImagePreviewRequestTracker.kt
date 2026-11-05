package com.cla.clip.master.ui.page.image

import com.cla.clip.base.general.repository.ImageCandidateData

/** 只比较影响响应内容的请求字段；宽高和显示顺序变化不触发重复元信息请求。 */
internal fun ImageCandidateData.previewRequestVersion(): List<String?> = listOf(url, referer, userAgent, cookie)

/** 只在页面主线程使用的请求票据门闩；版本含请求上下文，禁止记录或序列化。 */
internal class ImagePreviewRequestTracker {
    /** 每个候选只允许最新一张票据回写；票据以对象身份比较，清空后不会复活。 */
    private val active = mutableMapOf<String, Ticket>()

    /** 单次辅助请求的写入资格；不实现内容 toString，避免凭证意外进入日志。 */
    class Ticket internal constructor(
        /** 稳定候选键，仅用于内存关联，可能包含地址，禁止输出日志。 */
        val key: String,
        /** URL 与有效请求头的不可变快照，用于识别同一图片的资源版本。 */
        internal val version: List<String?>,
    )

    /** 新任务替换旧票据，即使版本相同也能阻止旧 finally 修改新任务。 */
    fun begin(key: String, version: List<String?>): Ticket = Ticket(key, version.toList()).also {
        active[key] = it
    }

    /** 判断已有缓存是否属于当前请求版本，不把稳定图片身份误当作资源版本。 */
    fun hasVersion(key: String, version: List<String?>): Boolean = active[key]?.version == version

    /** 成功、失败和清理分支共用写回门闩。 */
    fun isCurrent(ticket: Ticket): Boolean = active[ticket.key] === ticket

    /** 重新提取时取消所有旧票据资格，不保留请求上下文。 */
    fun clear() = active.clear()
}
