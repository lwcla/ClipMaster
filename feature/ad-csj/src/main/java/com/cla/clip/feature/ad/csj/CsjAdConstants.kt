package com.cla.clip.feature.ad.csj

/** 穿山甲广告源稳定 ID；设置、日志、选择器和诊断面板都使用该字符串。 */
internal const val CSJ_AD_SOURCE_ID = "csj"

/** 穿山甲详情页广告 auto 优先级；高于 debug 占位源。 */
internal const val CSJ_AD_PRIORITY = 100

/** 详情页穿山甲请求宿主侧超时，单位毫秒；超时后隐藏并释放资源。 */
internal const val CSJ_REQUEST_TIMEOUT_MS = 4_000L

/** 详情页穿山甲模板广告最大高度，单位 dp；防止广告挤压正文和底部操作区。 */
internal const val CSJ_DETAIL_NATIVE_MAX_HEIGHT_DP = 320
