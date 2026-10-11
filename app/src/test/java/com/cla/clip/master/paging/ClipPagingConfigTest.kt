package com.cla.clip.master.paging

import androidx.paging.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 保护首页重复点击列表回顶：浏览超过二百条后索引零仍须指向最新记录。 */
class ClipPagingConfigTest {
    /** 用实际生产配置和真实 presenter 验证，避免仅断言配置常量。 */
    @Test fun scrollingPastTwoHundredKeepsGlobalTopAtIndexZero() = runBlocking {
        /** 接收真实分页更新，无固定延时或真实数据库依赖。 */
        val changed = Channel<Unit>(Channel.CONFLATED)
        /** 使用非 Android 主线程，测试真实加载和页面保留。 */
        val presenter = object : PagingDataPresenter<Int>(Dispatchers.Unconfined) {
            /** 页面插入/删除后唤醒断言等待。 */
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<Int>) { changed.trySend(Unit) }
        }
        /** 四类列表实际使用的生产配置。 */
        val pager = Pager(clipPagingConfig()) { SequentialSource() }
        /** 模拟前台页面消费分页，测试结束显式取消。 */
        val collection = launch { pager.flow.collectLatest { presenter.collectFrom(it) } }
        try {
            withTimeout(5_000) { while (presenter.size == 0) changed.receive() }
            while (presenter.snapshot().items.last() < 399) {
                /** 每次请求后等待下一页到达，不把加载状态当作新数据。 */
                val previousLast = presenter.snapshot().items.last()
                presenter[presenter.size - 1]
                withTimeout(5_000) { while (presenter.snapshot().items.last() <= previousLast) changed.receive() }
            }
            assertEquals("回顶索引不能指向已回收窗口中段", 0, presenter[0])
        } finally {
            collection.cancelAndJoin()
            changed.close()
        }
    }

    /** 一千条合成顺序记录，不访问 Room 或用户数据。 */
    private class SequentialSource : PagingSource<Int, Int>() {
        /** 刷新固定从最新记录开始，避免锚点干扰回顶断言。 */
        override fun getRefreshKey(state: PagingState<Int, Int>): Int? = null

        /** key 为加载起点，下标越小表示记录越新。 */
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Int> {
            /** 本次加载的第一条记录。 */
            val start = params.key ?: 0
            /** 合成数据集的右开结束边界。 */
            val end = (start + params.loadSize).coerceAtMost(1000)
            return LoadResult.Page((start until end).toList(),
                if (start == 0) null else (start - 20).coerceAtLeast(0),
                if (end == 1000) null else end)
        }
    }
}
