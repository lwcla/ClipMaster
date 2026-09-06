package com.cla.clip.feature.torrent.search.provider.builtin.common

import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /** 用户信息与非 HTTPS 默认端口不得通过共同 URL 边界。 */
    @Test
    fun policyRejectsUserInfoAndNonDefaultPort() {
        /** 只允许合成 host，避免测试依赖真实站点。 */
        val policy = BuiltinProviderUrlPolicy(setOf("fixture.example"))
        /** 每个地址分别覆盖用户名、密码、组合或编码用户信息以及异常端口。 */
        val deniedUrls = listOf(
            "https://user@fixture.example/details/1",
            "https://:password@fixture.example/details/1",
            "https://user:password@fixture.example/details/1",
            "https://%75ser:%70assword@fixture.example/details/1",
            "https://fixture.example:8443/details/1",
        )

        /** 恶意地址必须沿用稳定、低敏的拒绝异常。 */
        for (deniedUrl in deniedUrls) {
            /** 当前地址的失败结果用于同时断言类型和无消息契约。 */
            val failure = runCatching { policy.requireHttpsUrl(deniedUrl) }.exceptionOrNull()
            assertTrue(failure is HttpDeniedException)
            assertNull(failure?.message)
        }
    }

    /** 默认 443、显式 443、合法相对地址和白名单内绝对地址保持可用。 */
    @Test
    fun policyKeepsValidHttpsPortAndResolutionBehavior() {
        /** 两个精确白名单 host 用于覆盖同站相对与白名单内跨站绝对解析。 */
        val policy = BuiltinProviderUrlPolicy(setOf("fixture.example", "cdn.fixture.example"))

        assertEquals(
            "https://fixture.example/details/1",
            policy.requireHttpsUrl("https://fixture.example/details/1").toString(),
        )
        assertEquals(
            "https://fixture.example/details/1",
            policy.requireHttpsUrl("https://fixture.example:443/details/1").toString(),
        )
        assertEquals(
            "https://fixture.example/details/1",
            policy.resolveOptionalHttpsUrl("https://fixture.example/search", "/details/1"),
        )
        assertEquals(
            "https://cdn.fixture.example/details/1",
            policy.resolveOptionalHttpsUrl(
                "https://fixture.example/search",
                "https://cdn.fixture.example/details/1",
            ),
        )
        assertNull(policy.resolveOptionalHttpsUrl("https://user@fixture.example/search", null))
        assertNull(policy.resolveOptionalHttpsUrl("https://fixture.example:8443/search", "   "))
    }

    /** 相对解析必须对 candidate 结果和非空 candidate 的污染 base 应用同一安全约束。 */
    @Test
    fun optionalResolutionRejectsUserInfoAndNonDefaultPortFromCandidateOrBase() {
        /** 只允许合成 host，所有拒绝都发生在纯内存 URL 解析阶段。 */
        val policy = BuiltinProviderUrlPolicy(setOf("fixture.example"))
        /** candidate 覆盖网络路径用户信息、绝对用户信息和异常端口。 */
        val deniedCandidates = listOf(
            "//user@fixture.example/details/1",
            "https://user:password@fixture.example/details/1",
            "https://fixture.example:8443/details/1",
        )

        /** 每个非空恶意 candidate 都必须抛出原有拒绝异常。 */
        for (deniedCandidate in deniedCandidates) {
            /** 当前解析失败用于核对非法非空候选仍然抛异常。 */
            val failure = runCatching {
                policy.resolveOptionalHttpsUrl("https://fixture.example/search", deniedCandidate)
            }.exceptionOrNull()
            assertTrue(failure is HttpDeniedException)
            assertNull(failure?.message)
        }

        /** base 覆盖用户信息与异常端口，candidate 保持合法非空以触发 base 校验。 */
        val deniedBases = listOf(
            "https://user@fixture.example/search",
            "https://fixture.example:8443/search",
        )
        /** 污染 base 不得成为相对解析上下文。 */
        for (deniedBase in deniedBases) {
            /** 当前 base 的解析失败用于核对原有异常类型与低敏消息。 */
            val failure = runCatching {
                policy.resolveOptionalHttpsUrl(deniedBase, "/details/1")
            }.exceptionOrNull()
            assertTrue(failure is HttpDeniedException)
            assertNull(failure?.message)
        }
    }
}
