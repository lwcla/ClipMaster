package com.cla.clip.feature.ad.uniad

import org.junit.Assert.assertEquals
import org.junit.Test

/** uni-ad 详情页信息流请求规格测试，确认一次详情页只请求一条广告。 */
class UniAdFeedRequestSpecTest {
    /** 默认请求数量固定为 1。 */
    @Test
    fun defaultCountIsOne() {
        /** 当前请求规格；只传入 adpid，count 应由常量兜底。 */
        val requestSpec = UniAdFeedRequestSpec(adpid = "1000000001")

        assertEquals(1, requestSpec.count)
    }

    /** 常量值保持为 1，避免详情页批量取广告。 */
    @Test
    fun detailNativeRequestCountConstantIsOne() {
        assertEquals(1, UNIAD_DETAIL_NATIVE_REQUEST_COUNT)
    }
}
