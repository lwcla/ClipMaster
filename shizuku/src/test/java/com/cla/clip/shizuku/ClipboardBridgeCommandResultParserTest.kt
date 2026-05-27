package com.cla.clip.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Provider 命令输出解析测试，保护 Shizuku 侧不要把命令执行成功误判为入库成功。 */
class ClipboardBridgeCommandResultParserTest {

    @Test
    /** Provider 明确返回 ok 且 saved=true 时才算通道成功。 */
    fun isSuccessfulReturnsTrueOnlyForOkAndSaved() {
        /** 模拟 Android `content call` 打印出的 Bundle 文本。 */
        val output = "Result: Bundle[{resultCode=ok, saved=true, readClip=true}]"

        assertTrue(ClipboardBridgeCommandResultParser.isSuccessful(0, output))
        assertTrue(ClipboardBridgeCommandResultParser.isReadClipSuccessful(0, output))
    }

    @Test
    /** exitCode 为 0 但 Provider 返回非 ok 时必须判定失败。 */
    fun isSuccessfulRejectsNonOkResultCode() {
        /** 模拟 Provider 被调用成功但悬浮窗失败的输出。 */
        val output = "Result: Bundle[{resultCode=overlay_failed, saved=false}]"

        assertFalse(ClipboardBridgeCommandResultParser.isSuccessful(0, output))
    }

    @Test
    /** exitCode 为 0 但缺少 saved=true 时必须判定失败。 */
    fun isSuccessfulRejectsMissingSavedFlag() {
        /** 模拟旧版本或异常 Provider 没有返回 saved 字段的输出。 */
        val output = "Result: Bundle[{resultCode=ok}]"

        assertFalse(ClipboardBridgeCommandResultParser.isSuccessful(0, output))
    }

    @Test
    /** 命令层输出 Error 时必须判定失败，即使 Bundle 字段看起来成功。 */
    fun isSuccessfulRejectsCommandErrorOutput() {
        /** 模拟 content 命令访问 Provider 失败但输出里夹带旧 Bundle 文本。 */
        val output = "Error while accessing provider:com.cla.clip.master.clipboard-bridge\nResult: Bundle[{resultCode=ok, saved=true}]"

        assertFalse(ClipboardBridgeCommandResultParser.isSuccessful(0, output))
    }

    @Test
    /** resultCode 和 saved 解析应兼容字段顺序变化。 */
    fun parserHandlesDifferentBundleFieldOrder() {
        /** 模拟 Bundle 字段顺序变化后的输出。 */
        val output = "Result: Bundle[{saved=true, iconStatus=placeholder, resultCode=ok}]"

        assertEquals(ClipboardBridgeContract.CODE_OK, ClipboardBridgeCommandResultParser.parseResultCode(output))
        assertEquals(true, ClipboardBridgeCommandResultParser.parseSaved(output))
        assertEquals(ClipboardBridgeContract.ICON_STATUS_PLACEHOLDER, ClipboardBridgeCommandResultParser.parseIconStatus(output))
    }

    @Test
    /** query_icon_state 只有明确返回 ok、shouldSyncIcon 和 reasonCode 时才算预判成功。 */
    fun isQueryIconStateSuccessfulAcceptsValidDecision() {
        /** 模拟 Provider 返回“需要继续同步图标”的图标预判结果。 */
        val output = "Result: Bundle[{resultCode=ok, shouldSyncIcon=true, iconDecisionReason=hash_changed}]"

        assertTrue(ClipboardBridgeCommandResultParser.isQueryIconStateSuccessful(0, output))
        assertEquals(true, ClipboardBridgeCommandResultParser.parseShouldSyncIcon(output))
        assertEquals(ClipboardBridgeContract.ICON_REASON_HASH_CHANGED, ClipboardBridgeCommandResultParser.parseIconDecisionReason(output))
    }

    @Test
    /** query_icon_state 缺少 shouldSyncIcon 或 reasonCode 时必须判定失败。 */
    fun isQueryIconStateSuccessfulRejectsIncompleteDecision() {
        /** 缺少 shouldSyncIcon 的预判输出。 */
        val missingShouldSyncOutput = "Result: Bundle[{resultCode=ok, iconDecisionReason=cache_hit}]"
        /** 缺少 reasonCode 的预判输出。 */
        val missingReasonOutput = "Result: Bundle[{resultCode=ok, shouldSyncIcon=false}]"

        assertFalse(ClipboardBridgeCommandResultParser.isQueryIconStateSuccessful(0, missingShouldSyncOutput))
        assertFalse(ClipboardBridgeCommandResultParser.isQueryIconStateSuccessful(0, missingReasonOutput))
    }

    @Test
    /** 缺少字段时解析结果返回 null，调用方再按失败处理。 */
    fun parserReturnsNullWhenFieldsMissing() {
        /** 模拟空 Bundle 输出。 */
        val output = "Result: Bundle[{}]"

        assertNull(ClipboardBridgeCommandResultParser.parseResultCode(output))
        assertNull(ClipboardBridgeCommandResultParser.parseSaved(output))
    }

    @Test
    /** commit_icon 只有明确返回 ok 且图标状态 saved/reused 时才算补图成功。 */
    fun isCommitIconSuccessfulAcceptsSavedAndReused() {
        /** 模拟新图标已经保存成功的 Provider 输出。 */
        val savedOutput = "Result: Bundle[{resultCode=ok, saved=true, iconStatus=saved}]"
        /** 模拟数据库已有同 hash 图标可复用的 Provider 输出。 */
        val reusedOutput = "Result: Bundle[{iconStatus=reused, resultCode=ok, saved=true}]"

        assertTrue(ClipboardBridgeCommandResultParser.isCommitIconSuccessful(0, savedOutput))
        assertTrue(ClipboardBridgeCommandResultParser.isCommitIconSuccessful(0, reusedOutput))
    }

    @Test
    /** exitCode 为 0 但图标仍是占位状态时不能把 commit_icon 判为成功。 */
    fun isCommitIconSuccessfulRejectsPlaceholderIconStatus() {
        /** 模拟 Provider 被调用成功但图标缺失的输出。 */
        val output = "Result: Bundle[{resultCode=ok, saved=true, iconStatus=placeholder}]"

        assertFalse(ClipboardBridgeCommandResultParser.isCommitIconSuccessful(0, output))
    }

    @Test
    /** commit_icon 失败必须同时考虑命令错误和 Provider 结果码。 */
    fun isCommitIconSuccessfulRejectsCommandErrorAndNonOkResult() {
        /** 模拟命令层报错但文本里夹带成功字段的输出。 */
        val commandErrorOutput = "Error while accessing provider\nResult: Bundle[{resultCode=ok, iconStatus=saved}]"
        /** 模拟 Provider 明确返回图标缺失的输出。 */
        val iconMissingOutput = "Result: Bundle[{resultCode=icon_missing, iconStatus=placeholder}]"

        assertFalse(ClipboardBridgeCommandResultParser.isCommitIconSuccessful(0, commandErrorOutput))
        assertFalse(ClipboardBridgeCommandResultParser.isCommitIconSuccessful(0, iconMissingOutput))
        assertFalse(ClipboardBridgeCommandResultParser.isCommitIconSuccessful(1, "Result: Bundle[{resultCode=ok, iconStatus=saved}]"))
    }
}
