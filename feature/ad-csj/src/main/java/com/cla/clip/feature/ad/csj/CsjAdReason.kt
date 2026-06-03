package com.cla.clip.feature.ad.csj

/** 穿山甲 adapter 低敏 reasonCode 集合，禁止拼接 SDK 原始响应或用户内容。 */
internal object CsjAdReason {
    /** 当前构建或运行时缺少 AppId、广告位 ID 或 SDK 依赖。 */
    const val CONFIG_MISSING = "csj_config_missing"

    /** 用户拒绝广告 SDK 所需的隐私同意。 */
    const val CONSENT_DENIED = "csj_consent_denied"

    /** 用户撤回广告 SDK 所需的隐私同意。 */
    const val CONSENT_REVOKED = "csj_consent_revoked"

    /** 用户同意的隐私政策版本不包含当前广告 SDK 清单。 */
    const val PRIVACY_VERSION_OUTDATED = "csj_privacy_version_outdated"

    /** 当前详情页命中本地敏感内容保护。 */
    const val SENSITIVE_DETAIL_HIDDEN = "csj_sensitive_detail_hidden"

    /** 当前进程不是应用主进程，不能初始化穿山甲 SDK。 */
    const val NOT_MAIN_PROCESS = "csj_not_main_process"

    /** 穿山甲 SDK 初始化失败。 */
    const val INIT_FAILED = "csj_init_failed"

    /** 当前网络不可用或请求前置网络检查失败。 */
    const val NO_NETWORK = "csj_no_network"

    /** 详情页广告请求超过宿主侧等待时间。 */
    const val REQUEST_TIMEOUT = "csj_request_timeout"

    /** 穿山甲返回无填充。 */
    const val NO_FILL = "csj_no_fill"

    /** 穿山甲广告加载失败。 */
    const val LOAD_FAILED = "csj_load_failed"

    /** v1 不支持下载类广告，命中后立即释放并隐藏。 */
    const val DOWNLOAD_AD_UNSUPPORTED = "csj_download_ad_unsupported"

    /** 穿山甲模板广告渲染失败。 */
    const val RENDER_FAILED = "csj_render_failed"

    /** 当前广告位已释放。 */
    const val RELEASED = "csj_released"

    /** 广告释放后到达的 SDK 迟到回调已被丢弃。 */
    const val LATE_CALLBACK_IGNORED = "csj_late_callback_ignored"

    /** 同一 requestNonce 内重复事件已被去重。 */
    const val EVENT_DEDUPLICATED = "csj_event_deduplicated"

    /** adapter 内部捕获到 Java/Kotlin 层异常。 */
    const val ADAPTER_EXCEPTION = "csj_adapter_exception"
}
