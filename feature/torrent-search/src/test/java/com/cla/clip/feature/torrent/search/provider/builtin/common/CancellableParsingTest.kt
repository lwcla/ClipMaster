package com.cla.clip.feature.torrent.search.provider.builtin.common

import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Provider JSON/DOM 大列表共用协作遍历的确定性取消测试。 */
class CancellableParsingTest {
    /** 子解析任务在首条映射后取消时，第二条及后续元素不得继续执行。 */
    @Test
    fun mapNotNullCancellableStopsBeforeNextElementAfterCancellation() = runTest {
        /** 实际进入转换回调的元素，用于观察取消后的遍历边界。 */
        val visitedElements = mutableListOf<Int>()
        /** 独立子任务避免取消测试自身的 runTest 根 Job。 */
        val parsingTask = async {
            /** element 是当前遍历值，首条主动取消用于锁定下一轮检查边界。 */
            listOf(1, 2, 3).mapNotNullCancellable { element ->
                visitedElements += element
                if (element == 1) currentCoroutineContext().cancel()
                element
            }
        }

        /** 子任务 await 的稳定取消结果。 */
        val failure = runCatching { parsingTask.await() }.exceptionOrNull()

        assertTrue(parsingTask.isCancelled)
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertEquals(listOf(1), visitedElements)
    }
}
