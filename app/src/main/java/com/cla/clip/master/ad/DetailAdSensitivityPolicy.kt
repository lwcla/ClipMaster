package com.cla.clip.master.ad

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 详情页广告敏感内容保护策略。
 *
 * 该策略只在本地读取剪贴正文并返回布尔值；不记录原文、不记录命中片段，也不把正文传给广告 adapter。
 */
@Singleton
class DetailAdSensitivityPolicy @Inject constructor() {

    /**
     * 判断当前详情内容是否应隐藏广告。
     *
     * 命中验证码、密码、token、密钥或银行卡号等高敏形态时返回 true，避免广告请求发生在敏感详情页。
     */
    fun shouldHideAds(content: String): Boolean {
        /** 规整后的正文，仅在本地内存参与匹配，不进入日志或广告请求。 */
        val normalizedContent = content.trim()
        if (normalizedContent.isBlank()) {
            return false
        }
        if (sensitiveKeywordRegex.containsMatchIn(normalizedContent)) {
            return true
        }
        if (verificationCodeRegex.containsMatchIn(normalizedContent)) {
            return true
        }
        return bankCardCandidateRegex.findAll(normalizedContent)
            .map { match -> match.value.filter(Char::isDigit) }
            .any { digits -> digits.length in 13..19 && passesLuhnCheck(digits) }
    }

    /**
     * Luhn 校验银行卡候选号。
     *
     * 只处理数字串；失败返回 false，避免普通长数字误判为高敏内容。
     */
    private fun passesLuhnCheck(digits: String): Boolean {
        /** 从右向左累计的校验和。 */
        var checksum = 0
        /** 当前位是否需要乘二；从最右侧校验位左边开始交替。 */
        var shouldDouble = false
        for (index in digits.length - 1 downTo 0) {
            /** 当前数字字符转成的数值。 */
            var digit = digits[index].digitToInt()
            if (shouldDouble) {
                digit *= 2
                if (digit > 9) {
                    digit -= 9
                }
            }
            checksum += digit
            shouldDouble = !shouldDouble
        }
        return checksum % 10 == 0
    }

    private companion object {
        /** 高敏关键词正则；只用于本地布尔判断，不输出命中内容。 */
        private val sensitiveKeywordRegex = Regex(
            pattern = "(?i)(password|passwd|pwd|token|secret|api[_-]?key|private[_-]?key|access[_-]?key|auth[_-]?code|验证码|校验码|密码|口令|令牌|密钥|银行卡|卡号|身份证)",
        )

        /** 验证码类短数字正则；结合关键词防止普通短数字误判。 */
        private val verificationCodeRegex = Regex(
            pattern = "(验证码|校验码|动态码|短信码|code)[^0-9]{0,12}[0-9]{4,8}",
            option = RegexOption.IGNORE_CASE,
        )

        /** 银行卡候选号正则；允许空格或短横线分隔，最终再做 Luhn 校验。 */
        private val bankCardCandidateRegex = Regex(
            pattern = "(?:\\d[ -]?){13,19}",
        )
    }
}
