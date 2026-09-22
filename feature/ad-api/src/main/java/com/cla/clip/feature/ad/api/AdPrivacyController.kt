package com.cla.clip.feature.ad.api

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/** 平台隐私快照；可请求不等同于个性化广告同意。 */
data class AdPrivacyState(
    /** 以隐私 SDK 当前判断为准，默认禁止请求。 */
    val canRequestAds: Boolean = false,
    /** 平台要求时宿主提供可操作的隐私选项入口。 */
    val privacyOptionsRequired: Boolean = false,
)

/** 隔离平台 SDK 类型的隐私入口，不持有宿主 Activity。 */
interface AdPrivacyController {
    /** 可观察的平台隐私快照，不进入用户备份。 */
    val state: StateFlow<AdPrivacyState>
    /** 每次应用启动刷新；只能在可交互 Activity 中显示必需表单。 */
    fun refresh(activity: Activity)
    /** 用户主动打开隐私选项后同步最新状态。 */
    fun showPrivacyOptions(activity: Activity)
}