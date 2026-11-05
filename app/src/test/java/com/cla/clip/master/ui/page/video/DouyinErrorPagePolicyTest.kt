package com.cla.clip.master.ui.page.video

import org.junit.Assert.*
import org.junit.Test

/** 仅用合成作品路径保护站点边界，避免其他网页被抖音错误规则关闭。 */
class DouyinErrorPagePolicyTest {
    /** 实际分享域名及大小写变体允许进入内容诊断。 */
    @Test fun acceptsKnownSharePageHosts() {
        // 域名用于协议白名单，作品号为合成值，不请求真实内容。
        val hosts = listOf("iesdouyin.com", "www.iesdouyin.com", "douyin.com", "www.douyin.com", "m.douyin.com")
        hosts.forEach { host ->
            assertTrue(host, isDouyinSharePage("https://$host/share/video/123/"))
        }
        assertTrue(isDouyinSharePage("https://WWW.IESDOUYIN.COM/share/video/123?test=1"))
    }

    /** 域名包含关键字不等于平台来源，非作品分享路径也不应扫描。 */
    @Test fun rejectsSpoofedHostsAndOtherPaths() {
        // 全部是不可访问的合成对抗地址或协议边界。
        val urls = listOf(null, "", "not a url", "https://douyin.com.example.invalid/share/video/123/",
            "https://example.invalid/douyin.com/share/video/123/", "https://evil.douyin.com/share/video/123/",
            "https://v.douyin.com/123/", "https://www.douyin.com/video/123/", "https://www.douyin.com/share/video/abc/",
            "file://www.douyin.com/share/video/123/", "https://www.douyin.com/share/video/123/comments")
        urls.forEach { url -> assertFalse(url, isDouyinSharePage(url)) }
    }
}
