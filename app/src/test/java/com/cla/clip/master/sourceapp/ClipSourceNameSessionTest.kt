package com.cla.clip.master.sourceapp

import com.cla.clip.base.general.entity.ClipShowEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 分页只装配历史名称，缓存去重和失败不得改变正文契约。 */
class ClipSourceNameSessionTest {
    /** 同包名在同一代次只查询一次，名称不会被其他 UI 图像会话改变。 */
    @Test fun repeatedSourceReusesNameSnapshot() = runBlocking {
        /** 合成名称查询次数。 */
        var reads = 0
        /** 后续数据源变化不修改已返回快照。 */
        var name = "first"
        /** 不依赖 Android 或图标 Manager 的轻量名称会话。 */
        val session = ClipSourceNameSession { reads++; name }
        /** 已装好的正文名称。 */
        val first = session.attach(clip("app"))
        name = "later"
        assertEquals("first", session.attach(clip("app")).sourceAppName)
        assertEquals("first", first.sourceAppName)
        assertEquals(1, reads)
    }

    /** 名称缺失也会缓存；空身份零查询，正文始终可显示。 */
    @Test fun missingNameIsCachedAndUnknownSourceSkipsRead() = runBlocking {
        /** 同一个缺失来源不能随每条正文重复查库。 */
        var reads = 0
        /** 模拟来源行不存在。 */
        val session = ClipSourceNameSession { reads++; null }
        assertNull(session.attach(clip("app")).sourceAppName)
        assertNull(session.attach(clip("app")).sourceAppName)
        assertEquals("text", session.attach(clip(null)).content)
        assertEquals(1, reads)
    }

    /** 名称异常只影响名称，正文不等待图像或被异常丢弃。 */
    @Test fun nameFailureStillDeliversContent() = runBlocking {
        /** 合成故障不使用真实系统消息。 */
        val session = ClipSourceNameSession { error("fixture") }
        assertEquals("text", session.attach(clip("app")).content)
    }

    /** 被隐藏/切换取消的名称查询不能交付旧结果。 */
    @Test(expected = CancellationException::class)
    fun cancellationPropagates() = runBlocking {
        /** 名称读取仍沿用取消向上传播的既有契约。 */
        val session = ClipSourceNameSession { throw CancellationException() }
        session.attach(clip("app"))
        Unit
    }

    /** 合成最小正文，不访问真实剪贴内容、Room 或时间格式化 API。 */
    private fun clip(packageName: String?) = ClipShowEntity(1, "text", 0, 0, 0, "time", packageName, false, false, null, null, null)
}
