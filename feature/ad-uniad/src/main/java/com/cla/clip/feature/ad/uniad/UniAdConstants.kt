package com.cla.clip.feature.ad.uniad

/** uni-ad 广告源稳定 ID；设置、日志、选择器和诊断面板都使用该字符串。 */
internal const val UNIAD_AD_SOURCE_ID = "uniad"

/** uni-ad 详情页广告 auto 优先级；高于 debug 占位源，并与真实国内源互斥打包。 */
internal const val UNIAD_AD_PRIORITY = 100

/** 详情页 uni-ad 请求宿主侧超时，单位毫秒；超时后隐藏并释放资源。 */
internal const val UNIAD_REQUEST_TIMEOUT_MS = 4_000L

/** 详情页 uni-ad 信息流广告最大高度，单位 dp；防止广告挤压正文和底部操作区。 */
internal const val UNIAD_DETAIL_NATIVE_MAX_HEIGHT_DP = 320

/** 详情页 uni-ad 单次请求数量；v1 固定为一条，禁止列表式批量取广告。 */
internal const val UNIAD_DETAIL_NATIVE_REQUEST_COUNT = 1
