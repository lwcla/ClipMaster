package com.cla.clip.feature.ad.uniad

import javax.inject.Inject
import javax.inject.Singleton

/** 提供当前构建注入的 uni-ad 配置，避免业务代码直接散读 BuildConfig 字段。 */
@Singleton
internal class UniAdConfigProvider @Inject constructor() {
    /** 当前构建的 uni-ad 配置快照；BuildConfig 值在运行期不变化。 */
    val config: UniAdConfig = UniAdConfig(
        appId = BuildConfig.UNIAD_APP_ID,
        unionId = BuildConfig.UNIAD_UNION_ID,
        detailNativeAdpid = BuildConfig.UNIAD_DETAIL_NATIVE_ADPID,
        useTestAdpid = BuildConfig.UNIAD_USE_TEST_ADPID,
        sdkDependencyEnabled = BuildConfig.UNIAD_SDK_DEPENDENCY_ENABLED,
        sdkVersion = BuildConfig.UNIAD_SDK_VERSION,
        requiredPrivacyPolicyVersion = BuildConfig.UNIAD_REQUIRED_PRIVACY_POLICY_VERSION,
    )
}
