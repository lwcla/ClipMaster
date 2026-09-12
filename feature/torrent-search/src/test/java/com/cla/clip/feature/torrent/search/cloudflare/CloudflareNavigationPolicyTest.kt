package com.cla.clip.feature.torrent.search.cloudflare

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cloudflare 挑战页同源导航边界测试。 */
class CloudflareNavigationPolicyTest {
    /** 同 scheme、host 与有效端口的 HTTP/HTTPS 页面允许加载。 */
    @Test
    fun allowsOnlySameOriginHttpOrHttps() {
        /** httpsPolicy 固定初始 HTTPS 来源。 */
        val httpsPolicy = CloudflareNavigationPolicy("https://provider.example/challenge")
        /** httpPolicy 固定自用 HTTP 非标准端口来源。 */
        val httpPolicy = CloudflareNavigationPolicy("http://provider.example:8080/challenge")

        assertTrue(httpsPolicy.allows("https://provider.example/cdn-cgi/challenge-platform/step"))
        assertTrue(httpPolicy.allows("http://provider.example:8080/next"))
        assertFalse(httpsPolicy.allows("http://provider.example/challenge"))
        assertFalse(httpsPolicy.allows("https://other.example/challenge"))
        assertFalse(httpPolicy.allows("http://provider.example:8081/next"))
        assertFalse(httpsPolicy.allows("file:///data/local/tmp/page"))
        assertFalse(httpsPolicy.allows("javascript:alert(1)"))
    }

    /** 无效初始地址必须形成拒绝全部导航的策略。 */
    @Test
    fun invalidOriginRejectsAllNavigation() {
        /** policy 接收损坏初始地址并保持 fail-closed。 */
        val policy = CloudflareNavigationPolicy("not a url")

        assertFalse(policy.allows("https://provider.example/"))
    }
}
