package com.cla.clip.feature.torrent.search.input

import com.cla.clip.feature.torrent.search.api.TorrentSearchLaunchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 外部 Intent 动作、文本来源和稳定启动来源解析测试。 */
class TorrentSearchIntentResolverTest {
    /** 显式宿主启动只读取 feature extra，并保留宿主提供的稳定来源 code。 */
    @Test
    fun resolveUsesExplicitQueryAndLaunchSourceForUnknownAction() {
        /** request 验证普通显式启动不会误读系统分享字段。 */
        val request = TorrentSearchIntentResolver.resolve(
            TorrentSearchIntentPayload(
                action = null,
                mimeType = null,
                explicitQuery = "  linux\niso  ",
                explicitLaunchSourceCode = TorrentSearchLaunchSource.ClipDetail.code,
                searchQuery = "ignored-search",
                sharedText = "ignored-share",
                processedText = "ignored-process",
            )
        )

        assertEquals("linuxiso", request.query)
        assertEquals(TorrentSearchLaunchSource.ClipDetail, request.launchSource)
        assertTrue(request.canAutoSearch)
        assertFalse(request.wasTruncated)
    }

    /** 三个系统 action 必须分别读取自己的 extra，并覆盖伪造的显式来源。 */
    @Test
    fun resolveMapsSystemActionsToTheirOwnTextAndStableSource() {
        /** actionCases 的四元组依次表达 action、系统文本、期望文本和期望来源。 */
        val actionCases = listOf(
            SystemActionCase(
                action = "android.intent.action.SEARCH",
                systemText = "search-query",
                expectedQuery = "search-query",
                expectedSource = TorrentSearchLaunchSource.ActionSearch,
            ),
            SystemActionCase(
                action = "android.intent.action.SEND",
                systemText = "shared-query",
                expectedQuery = "shared-query",
                expectedSource = TorrentSearchLaunchSource.Send,
            ),
            SystemActionCase(
                action = "android.intent.action.PROCESS_TEXT",
                systemText = "selected-query",
                expectedQuery = "selected-query",
                expectedSource = TorrentSearchLaunchSource.ProcessText,
            ),
        )

        /** actionCase 是当前待验证的系统入口及其独立字段契约。 */
        actionCases.forEach { actionCase ->
            /** payload 只给当前 action 对应字段写入期望文本，其他字段使用干扰值。 */
            val payload = TorrentSearchIntentPayload(
                action = actionCase.action,
                mimeType = "text/plain",
                explicitQuery = "ignored-explicit",
                explicitLaunchSourceCode = TorrentSearchLaunchSource.Mine.code,
                searchQuery = if (actionCase.expectedSource == TorrentSearchLaunchSource.ActionSearch) actionCase.systemText else "ignored-search",
                sharedText = if (actionCase.expectedSource == TorrentSearchLaunchSource.Send) actionCase.systemText else "ignored-share",
                processedText = if (actionCase.expectedSource == TorrentSearchLaunchSource.ProcessText) actionCase.systemText else "ignored-process",
            )
            /** request 必须只由 action 对应字段和固定系统来源产生。 */
            val request = TorrentSearchIntentResolver.resolve(payload)

            assertEquals(actionCase.expectedQuery, request.query)
            assertEquals(actionCase.expectedSource, request.launchSource)
            assertTrue(request.canAutoSearch)
        }
    }

    /** SEND/PROCESS_TEXT 不是 text/plain 时不得把任意 MIME 内容当作搜索词。 */
    @Test
    fun resolveRejectsNonPlainTextSystemPayloads() {
        /** rejectedActions 覆盖两个声明 text/plain 的系统入口。 */
        val rejectedActions = listOf(
            "android.intent.action.SEND" to "sharedText",
            "android.intent.action.PROCESS_TEXT" to "processedText",
        )

        /** actionAndField 是当前系统 action 及仅用于区分测试输入的字段标签。 */
        rejectedActions.forEach { actionAndField ->
            /** payload 为图片 MIME 注入文本，resolver 必须按空输入处理。 */
            val payload = TorrentSearchIntentPayload(
                action = actionAndField.first,
                mimeType = "image/png",
                explicitQuery = "ignored-explicit",
                explicitLaunchSourceCode = TorrentSearchLaunchSource.Mine.code,
                sharedText = if (actionAndField.second == "sharedText") "unsafe-share" else null,
                processedText = if (actionAndField.second == "processedText") "unsafe-process" else null,
            )
            /** request 保留真实系统来源，但不得携带被拒绝的文本或触发自动搜索。 */
            val request = TorrentSearchIntentResolver.resolve(payload)

            assertEquals("", request.query)
            assertFalse(request.canAutoSearch)
        }
    }

    /** 系统入口同样必须复用 512 字符边界，超限只预填且禁止自动搜索。 */
    @Test
    fun resolveTruncatesSystemTextAndBlocksAutoSearch() {
        /** request 使用比协议上限多一字符的 ACTION_SEARCH 查询触发截断分支。 */
        val request = TorrentSearchIntentResolver.resolve(
            TorrentSearchIntentPayload(
                action = "android.intent.action.SEARCH",
                searchQuery = "q".repeat(513),
            )
        )

        assertEquals(512, request.query.length)
        assertTrue(request.wasTruncated)
        assertFalse(request.canAutoSearch)
    }
}

/** 系统 action 表格测试的独立期望，避免用 resolver 自身逻辑计算断言。 */
private data class SystemActionCase(
    /** Android 系统 action 字符串。 */
    val action: String,
    /** 当前 action 对应 extra 中的合成文本。 */
    val systemText: String,
    /** 手工固定的期望清洗后查询。 */
    val expectedQuery: String,
    /** 手工固定的稳定启动来源。 */
    val expectedSource: TorrentSearchLaunchSource,
)
