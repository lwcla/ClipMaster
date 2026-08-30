package com.cla.clip.feature.torrent.search.api

/**
 * TorrentSearch 的稳定启动来源。
 *
 * [code] 会进入 Intent 和后续持久化协议，因此调用方只能读写该字段，不能依赖枚举名称或顺序。
 */
enum class TorrentSearchLaunchSource(
    /** 跨版本稳定的协议值；新增来源时必须保持唯一且不能复用历史值。 */
    val code: String,
) {
    /** 从“我的”页功能入口启动。 */
    Mine("mine"),

    /** 从剪贴详情页快捷动作启动。 */
    ClipDetail("clip_detail"),

    /** 从系统 ACTION_SEARCH 入口启动。 */
    ActionSearch("action_search"),

    /** 从系统 ACTION_SEND 入口启动。 */
    Send("send"),

    /** 从系统 ACTION_PROCESS_TEXT 入口启动。 */
    ProcessText("process_text"),

    /** 未识别或缺失来源的安全回退，防止外部 Intent 因协议升级导致崩溃。 */
    Unknown("unknown");

    companion object {
        /**
         * 把外部稳定 code 解析为来源。
         *
         * null、空白或未来版本的未知值统一回退到 [Unknown]，不抛异常也不猜测调用方。
         */
        fun fromCode(code: String?): TorrentSearchLaunchSource {
            /** 与外部 code 精确匹配的已知来源；协议值区分大小写以避免隐式归一化改变语义。 */
            val matchedSource = entries.firstOrNull { source -> source.code == code }
            return matchedSource ?: Unknown
        }
    }
}
