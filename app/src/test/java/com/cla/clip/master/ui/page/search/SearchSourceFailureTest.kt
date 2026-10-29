package com.cla.clip.master.ui.page.search

import androidx.paging.PagingData
import com.cla.clip.base.general.dao.data.SourceAppCandidate
import com.cla.clip.base.general.entity.ClipShowEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 同一个来源数据源失败时，菜单与结果两条订阅都必须有明确失败边界。 */
class SearchSourceFailureTest {
    /** 菜单失败不向 ViewModel 作用域外溢，重试调用可恢复候选。 */
    @Test fun menuFailureDoesNotCrashAndNextAttemptCanRecover() = runBlocking {
        /** 初次读取抛出合成错误，禁止使用真实数据库错误文本。 */
        val failed = runCatching { searchMenuCandidates { flow { error("fixture failure") } }.first() }
        assertTrue("候选失败应降级为空而非终止页面", failed.isSuccess)
        assertEquals(emptyList<SourceAppCandidate>(), failed.getOrThrow())
        /** 后续重试的真实候选投影。 */
        val expected = listOf(SourceAppCandidate("fixture", "会议"))
        assertEquals(expected, searchMenuCandidates { flowOf(expected) }.first())
    }

    /** 数据读取失败必须交付可显示错误的 PagingData，而不是异常终止缓存流。 */
    @Test fun resultFailureProducesPagingData() = runBlocking {
        /** 合成失败走生产异常转换器，详细 LoadState 在缓存消费测试中验证。 */
        val failed = runCatching { flow<PagingData<ClipShowEntity>> { error("fixture failure") }.catchSearchLoadFailure().first() }
        assertTrue("搜索结果失败应转为分页错误状态", failed.isSuccess)
    }

    /** 生命周期取消不是可恢复错误，不允许菜单或结果将其吞掉。 */
    @Test fun cancellationStillPropagates() = runBlocking {
        assertTrue(runCatching { searchMenuCandidates { flow { throw CancellationException("fixture") } }.first() }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { flow<PagingData<ClipShowEntity>> { throw CancellationException("fixture") }.catchSearchLoadFailure().first() }.exceptionOrNull() is CancellationException)
    }
}
