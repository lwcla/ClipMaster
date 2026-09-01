package com.cla.clip.feature.torrent.search.provider.builtin.common

import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内置 Provider HTTPS 与精确 host 白名单的安全回归测试。 */
class BuiltinProviderUrlPolicyTest {
    /** 同 host HTTPS 相对地址可解析，HTTP、子域名和外部 host 都必须拒绝。 */
    @Test
    fun policyAllowsOnlyExactHttpsHosts() {
        /** 只允许 fixture.example 精确 host 的待测白名单。 */
        val policy = BuiltinProviderUrlPolicy(setOf("fixture.example"))

        assertEquals(
            "https://fixture.example/details/1",
            policy.resolveOptionalHttpsUrl("https://fixture.example/search", "/details/1"),
        )
        assertTrue(runCatching { policy.requireHttpsUrl("http://fixture.example/details/1") }.exceptionOrNull() is HttpDeniedException)
        assertTrue(runCatching { policy.requireHttpsUrl("https://sub.fixture.example/details/1") }.exceptionOrNull() is HttpDeniedException)
        assertTrue(runCatching { policy.requireHttpsUrl("https://evil.invalid/details/1") }.exceptionOrNull() is HttpDeniedException)
    }

    /** 拒绝异常不得包含恶意 URL 原文或其中的敏感标记。 */
    @Test
    fun rejectionDoesNotEchoOriginalUrl() {
        /** 只允许 fixture.example 的白名单。 */
        val policy = BuiltinProviderUrlPolicy(setOf("fixture.example"))
        /** 放入地址中用于检查异常不回显的敏感标记。 */
        val sensitiveMarker = "SENSITIVE_URL_MARKER"
        /** 恶意 host 导致的稳定低敏拒绝异常。 */
        val failure = runCatching {
            policy.requireHttpsUrl("https://evil.invalid/$sensitiveMarker")
        }.exceptionOrNull()

        assertTrue(failure is HttpDeniedException)
        assertFalse(failure.toString().contains(sensitiveMarker))
    }
}
