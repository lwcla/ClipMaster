package com.cla.clip.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Provider 命令输出解析测试，保护 Shizuku 侧不要把命令执行成功误判为入库成功。 */
class ClipboardBridgeCommandResultParserTest {

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

    @Test
    /** commit_clip 接受真实保存、重复跳过和来源过滤三种已处理完成状态。 */
    fun isCommitClipSuccessfulAcceptsSavedDuplicateAndBlockedStatus() {
        /** 模拟新剪贴记录已经保存成功的 Provider 输出。 */
        val savedOutput = "Result: Bundle[{resultCode=ok, clipCommitted=true, clipStatus=saved}]"
        /** 模拟命中现有去重语义的 Provider 输出。 */
        val duplicateOutput = "Result: Bundle[{clipStatus=duplicate_or_empty, resultCode=ok, clipCommitted=false}]"
        /** 模拟来源 App 命中过滤名单的 Provider 输出。 */
        val blockedOutput = "Result: Bundle[{clipStatus=source_app_blocked, resultCode=ok, clipCommitted=false}]"

        assertTrue(ClipboardBridgeCommandResultParser.isCommitClipSuccessful(0, savedOutput))
        assertTrue(ClipboardBridgeCommandResultParser.isCommitClipSuccessful(0, duplicateOutput))
        assertTrue(ClipboardBridgeCommandResultParser.isCommitClipSuccessful(0, blockedOutput))
        assertEquals(true, ClipboardBridgeCommandResultParser.parseClipCommitted(savedOutput))
        assertEquals(false, ClipboardBridgeCommandResultParser.parseClipCommitted(blockedOutput))
        assertEquals(ClipboardBridgeContract.CLIP_STATUS_DUPLICATE_OR_EMPTY, ClipboardBridgeCommandResultParser.parseClipStatus(duplicateOutput))
        assertEquals(ClipboardBridgeContract.CLIP_STATUS_SOURCE_APP_BLOCKED, ClipboardBridgeCommandResultParser.parseClipStatus(blockedOutput))
    }

    @Test
    /** commit_clip 对 payload 缺失、类型不支持和命令错误都必须判定失败。 */
    fun isCommitClipSuccessfulRejectsPayloadFailures() {
        /** 模拟 payload 文件缺失的 Provider 输出。 */
        val missingOutput = "Result: Bundle[{resultCode=payload_missing, clipStatus=payload_missing}]"
        /** 模拟第一版不支持的 URI/Intent 剪贴类型输出。 */
        val unsupportedOutput = "Result: Bundle[{resultCode=unsupported_clip_type, clipStatus=unsupported_clip_type}]"
        /** 模拟命令层报错但文本里夹带成功字段的输出。 */
        val commandErrorOutput = "Error while accessing provider\nResult: Bundle[{resultCode=ok, clipStatus=saved}]"

        assertFalse(ClipboardBridgeCommandResultParser.isCommitClipSuccessful(0, missingOutput))
        assertFalse(ClipboardBridgeCommandResultParser.isCommitClipSuccessful(0, unsupportedOutput))
        assertFalse(ClipboardBridgeCommandResultParser.isCommitClipSuccessful(0, commandErrorOutput))
    }

    @Test
    /** query_shizuku_process 只有返回 ok 且进程名非空时才算身份查询成功。 */
    fun isQueryShizukuProcessSuccessfulAcceptsProcessName() {
        /** 模拟 Provider 返回最新 Shizuku 完整进程名和连接请求状态。 */
        val output = "Result: Bundle[{resultCode=ok, shizukuProcessName=com.cla.clip.master:shizuku_9_001234, connectRequested=true, reasonCode=identity_query}]"

        assertTrue(ClipboardBridgeCommandResultParser.isQueryShizukuProcessSuccessful(0, output))
        assertEquals("com.cla.clip.master:shizuku_9_001234", ClipboardBridgeCommandResultParser.parseShizukuProcessName(output))
        assertEquals(true, ClipboardBridgeCommandResultParser.parseConnectRequested(output))
        assertEquals(ClipboardBridgeContract.REASON_IDENTITY_QUERY, ClipboardBridgeCommandResultParser.parseReasonCode(output))
    }

    @Test
    /** query_shizuku_process 缺少进程名、连接状态、原因码、非 ok 或命令错误时必须判定身份不确定。 */
    fun isQueryShizukuProcessSuccessfulRejectsIncompleteOutput() {
        /** 缺少进程名的身份查询输出。 */
        val missingProcessNameOutput = "Result: Bundle[{resultCode=ok, connectRequested=true, reasonCode=identity_query}]"
        /** 缺少连接请求状态的身份查询输出。 */
        val missingConnectRequestedOutput = "Result: Bundle[{resultCode=ok, shizukuProcessName=com.cla.clip.master:shizuku_9_001234, reasonCode=identity_query}]"
        /** 缺少原因码的身份查询输出。 */
        val missingReasonOutput = "Result: Bundle[{resultCode=ok, shizukuProcessName=com.cla.clip.master:shizuku_9_001234, connectRequested=true}]"
        /** Provider 明确返回进程名缺失的身份查询输出。 */
        val missingExpectedOutput = "Result: Bundle[{resultCode=shizuku_process_missing, reasonCode=missing_expected_process_name}]"
        /** 命令层报错但夹带旧成功字段的输出。 */
        val commandErrorOutput = "Error while accessing provider\nResult: Bundle[{resultCode=ok, shizukuProcessName=com.cla.clip.master:shizuku_9_001234, connectRequested=true, reasonCode=identity_query}]"

        assertFalse(ClipboardBridgeCommandResultParser.isQueryShizukuProcessSuccessful(0, missingProcessNameOutput))
        assertFalse(ClipboardBridgeCommandResultParser.isQueryShizukuProcessSuccessful(0, missingConnectRequestedOutput))
        assertFalse(ClipboardBridgeCommandResultParser.isQueryShizukuProcessSuccessful(0, missingReasonOutput))
        assertFalse(ClipboardBridgeCommandResultParser.isQueryShizukuProcessSuccessful(0, missingExpectedOutput))
        assertFalse(ClipboardBridgeCommandResultParser.isQueryShizukuProcessSuccessful(0, commandErrorOutput))
        assertEquals(
            ClipboardBridgeContract.REASON_MISSING_EXPECTED_PROCESS_NAME,
            ClipboardBridgeCommandResultParser.parseReasonCode(missingExpectedOutput)
        )
    }

    @Test
    /** 身份查询解析器应独立解析连接跳过原因，不混入剪贴保存成功语义。 */
    fun parserExtractsShizukuProcessConnectSkipReason() {
        /** 模拟 Provider 判断当前 binder 已经是最新进程而跳过重复 bind 的输出。 */
        val output = "Result: Bundle[{connectSkipReason=alive_same_process, resultCode=ok, shizukuProcessName=com.cla.clip.master:shizuku_9_001234, connectRequested=true, reasonCode=identity_query}]"

        assertEquals("alive_same_process", ClipboardBridgeCommandResultParser.parseConnectSkipReason(output))
        assertEquals(null, ClipboardBridgeCommandResultParser.parseSaved(output))
    }

    @Test
    /** app 唤醒命令解析只接受 NoDisplay Activity 输出，并拒绝旧前台服务输出。 */
    fun appWakeParserHandlesActivityOutputOnly() {
        /** 旧前台服务被系统接受时的输出；新链路必须拒绝这类输出，避免回退到已删除服务。 */
        val serviceSuccess = "Starting service: Intent { cmp=com.cla.clip.master/com.cla.clip.master.service.ClipboardService }"
        /** NoDisplay Activity 被系统接受时的输出。 */
        val activitySuccess = "Starting: Intent { cmp=com.cla.clip.master/.wake.ShizukuWakeActivity }"
        /** Activity component 不存在时的系统错误输出。 */
        val activityMissing = "Error type 3\nError: Activity class {com.cla.clip.master/com.cla.clip.master.wake.ShizukuWakeActivity} does not exist."

        assertFalse(ClipboardBridgeCommandResultParser.isAppWakeCommandSuccessful(0, serviceSuccess))
        assertTrue(ClipboardBridgeCommandResultParser.isAppWakeCommandSuccessful(0, activitySuccess))
        assertFalse(ClipboardBridgeCommandResultParser.isAppWakeCommandSuccessful(0, activityMissing))
    }

    @Test
    /** Provider 缺失解析必须同时命中本应用 authority 和冷启动缺失错误片段。 */
    fun providerMissingParserRequiresAuthorityAndMissingMessage() {
        /** 本应用 Provider authority。 */
        val authority = "com.cla.clip.master.clipboard-bridge"
        /** 三星 S10 上 content call 找不到 Provider 时的典型输出。 */
        val missingOutput = "Error while accessing provider:$authority\njava.lang.IllegalStateException: Could not find provider: $authority"
        /** 其他 Provider 的缺失输出，不能误触发本应用唤醒重试。 */
        val otherAuthorityOutput = "Error while accessing provider:other.provider\njava.lang.IllegalStateException: Could not find provider: other.provider"
        /** 本应用 Provider 返回结构化错误时，不应被当成冷启动缺失。 */
        val structuredFailure = "Result: Bundle[{resultCode=invalid_args, reasonCode=bad_request}]"

        assertTrue(ClipboardBridgeCommandResultParser.isProviderMissingForColdStart(missingOutput, authority))
        assertFalse(ClipboardBridgeCommandResultParser.isProviderMissingForColdStart(otherAuthorityOutput, authority))
        assertFalse(ClipboardBridgeCommandResultParser.isProviderMissingForColdStart(structuredFailure, authority))
    }
}
