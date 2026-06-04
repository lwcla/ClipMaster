package com.cla.clip.feature.ad.uniad

/** uni-ad adapter 低敏 reasonCode 集合，禁止拼接 SDK 原始响应、广告位 ID 或用户内容。 */
internal object UniAdReason {
    /** 当前构建或运行时缺少 AppId、联盟 ID、adpid 或 SDK 依赖。 */
    const val CONFIG_MISSING = "uniad_config_missing"

    /** 当前包未携带或无法访问 uni-ad SDK。 */
    const val SDK_MISSING = "uniad_sdk_missing"

    /** 构建期校验发现 uni-ad AAR SHA-256 不匹配。 */
    const val SDK_CHECKSUM_MISMATCH = "uniad_sdk_checksum_mismatch"

    /** 当前渠道不支持详情页信息流广告。 */
    const val CHANNEL_UNSUPPORTED = "uniad_channel_unsupported"

    /** 当前 AndroidView context 无法找到 Activity，不能调用 getFeedAdView(Activity)。 */
    const val ACTIVITY_MISSING = "uniad_activity_missing"

    /** 用户拒绝广告 SDK 所需的隐私同意。 */
    const val CONSENT_DENIED = "uniad_consent_denied"

    /** 用户撤回或尚未明确授予广告 SDK 隐私同意。 */
    const val CONSENT_REVOKED = "uniad_consent_revoked"

    /** 用户同意的隐私政策版本不包含当前 uni-ad SDK 清单。 */
    const val PRIVACY_VERSION_OUTDATED = "uniad_privacy_version_outdated"

    /** 当前详情页命中本地敏感内容保护。 */
    const val SENSITIVE_DETAIL_HIDDEN = "uniad_sensitive_detail_hidden"

    /** 当前进程不是应用主进程，不能初始化 uni-ad SDK。 */
    const val NOT_MAIN_PROCESS = "uniad_not_main_process"

    /** 远程或预留 kill switch 关闭了 uni-ad 能力。 */
    const val REMOTE_KILL_SWITCH = "uniad_remote_kill_switch"

    /** uni-ad SDK 初始化失败。 */
    const val INIT_FAILED = "uniad_init_failed"

    /** 当前网络不可用或请求前置网络检查失败。 */
    const val NO_NETWORK = "uniad_no_network"

    /** 详情页广告请求超过宿主侧等待时间。 */
    const val REQUEST_TIMEOUT = "uniad_request_timeout"

    /** uni-ad 返回无填充。 */
    const val NO_FILL = "uniad_no_fill"

    /** uni-ad 广告加载失败。 */
    const val LOAD_FAILED = "uniad_load_failed"

    /** v1 不支持下载类广告，命中后立即释放并隐藏。 */
    const val DOWNLOAD_AD_UNSUPPORTED = "uniad_download_ad_unsupported"

    /** uni-ad 信息流广告渲染失败。 */
    const val RENDER_FAILED = "uniad_render_failed"

    /** 用户通过广告关闭入口关闭了当前广告。 */
    const val CLOSED_BY_USER = "uniad_closed_by_user"

    /** 当前广告位已释放。 */
    const val RELEASED = "uniad_released"

    /** 广告释放后到达的 SDK 迟到回调已被丢弃。 */
    const val LATE_CALLBACK_IGNORED = "uniad_late_callback_ignored"

    /** 同一 requestNonce 内重复事件已被去重。 */
    const val EVENT_DEDUPLICATED = "uniad_event_deduplicated"

    /** adapter 内部捕获到 Java/Kotlin 层异常。 */
    const val ADAPTER_EXCEPTION = "uniad_adapter_exception"
}
