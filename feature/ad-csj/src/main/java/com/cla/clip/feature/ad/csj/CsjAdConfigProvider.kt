package com.cla.clip.feature.ad.csj

import javax.inject.Inject
import javax.inject.Singleton

/** 提供当前构建注入的穿山甲配置，避免业务代码直接散读 BuildConfig 字段。 */
@Singleton
internal class CsjAdConfigProvider @Inject constructor() {
    /** 当前构建的穿山甲配置快照；BuildConfig 值在运行期不变化。 */
    val config: CsjAdConfig = CsjAdConfig(
        appId = BuildConfig.CSJ_APP_ID,
        detailNativeAdSlotId = BuildConfig.CSJ_DETAIL_NATIVE_AD_SLOT_ID,
        useTestAdSlot = BuildConfig.CSJ_USE_TEST_AD_SLOT,
        sdkDependencyEnabled = BuildConfig.CSJ_SDK_DEPENDENCY_ENABLED,
        sdkVersion = BuildConfig.CSJ_SDK_VERSION,
        requiredPrivacyPolicyVersion = BuildConfig.CSJ_REQUIRED_PRIVACY_POLICY_VERSION,
    )
}
