package com.cla.clip.master.ui.page.image

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 用虚拟等待记录业务时点，无真实延时或 WebView 依赖。 */
class ImageProbeBudgetTest {
    /** 有候选的无限页面在一分钟提示、两分钟冻结结果。 */
    @Test fun growingPageStopsAtTwoMinutes() = runBlocking {
        // 累计时间由注入等待推进，不受机器负载影响。
        var elapsed = 0L
        // 记录用户可见动作的实际时点。
        val events = mutableListOf<Long>()
        runImageProbeBudget({ true }, { events += elapsed }, { events += elapsed }, { elapsed += it })
        assertEquals(listOf(60_000L, 120_000L), events)
    }

    /** 空页面只等十秒，退出页面后的取消不触发完成回调。 */
    @Test fun emptyPageAndCancellationHaveSeparateExits() = runBlocking {
        // 空页面的累计等待。
        var elapsed = 0L
        runImageProbeBudget({ false }, { fail("空页面不应进入长耗时提示") }, {}, { elapsed += it })
        assertEquals(10_000L, elapsed)
        try {
            runImageProbeBudget({ true }, { fail() }, { fail() }, { throw CancellationException() })
            fail("取消必须向调用方传播")
        } catch (_: CancellationException) { /* 页面取消正常结束，不转成业务失败。 */ }
    }
}
