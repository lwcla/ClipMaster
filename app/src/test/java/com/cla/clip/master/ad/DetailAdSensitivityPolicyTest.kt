package com.cla.clip.master.ad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 详情页广告敏感内容保护策略测试，避免高敏剪贴触发真实广告请求。 */
class DetailAdSensitivityPolicyTest {
    /** 被测策略；只依赖纯 Kotlin 字符串匹配。 */
    private val policy = DetailAdSensitivityPolicy()

    /** 验证码内容应隐藏广告。 */
    @Test
    fun shouldHideAdsWhenVerificationCodeIsPresent() {
        assertTrue(policy.shouldHideAds("您的验证码是 123456，5 分钟内有效"))
    }

    /** token 或密钥内容应隐藏广告。 */
    @Test
    fun shouldHideAdsWhenTokenKeywordIsPresent() {
        assertTrue(policy.shouldHideAds("api_key = abcdef123456"))
    }

    /** 通过 Luhn 的银行卡候选号应隐藏广告。 */
    @Test
    fun shouldHideAdsWhenBankCardCandidatePassesLuhn() {
        assertTrue(policy.shouldHideAds("卡号 4111 1111 1111 1111"))
    }

    /** 普通正文不应隐藏广告。 */
    @Test
    fun shouldNotHideAdsForNormalContent() {
        assertFalse(policy.shouldHideAds("今天整理一下链接和资料，晚上继续看。"))
    }

    /** 不通过 Luhn 的长数字不应单独触发银行卡保护。 */
    @Test
    fun shouldNotHideAdsWhenLongNumberFailsLuhn() {
        assertFalse(policy.shouldHideAds("订单号 1234 5678 9012 3456"))
    }
}
