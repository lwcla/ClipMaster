package com.cla.clip.base.general.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** 安装级纯数字 ID 生成测试，保护 Shizuku 进程名和备份设备标识的基础格式。 */
class NumericInstallIdGeneratorTest {
    @Test
    /** 生成结果必须是固定长度纯数字字符串，避免进程名中混入 UUID 横线。 */
    fun generateCreatesFixedLengthDigits() {
        /** 测试用伪随机源；固定种子让断言只关注格式，不依赖真实熵。 */
        val random = Random(1234L)

        /** 生成出的安装 ID；业务要求保留字符串形式，避免前导 0 被 Long 转换丢失。 */
        val installId = NumericInstallIdGenerator.generate(random = random, length = 20)

        assertEquals(20, installId.length)
        assertTrue(installId.all { digit -> digit in '0'..'9' })
    }

    @Test
    /** 随机首位为 0 时也必须保留前导 0，确保 ID 不被数值化。 */
    fun generateKeepsLeadingZero() {
        /** 固定返回 0 的随机源；用于模拟最容易被数值转换破坏的前导 0 场景。 */
        val zeroRandom = object : Random() {
            /** 返回固定 0，确保每一位数字都是 0。 */
            override fun nextInt(bound: Int): Int = 0
        }

        /** 生成出的安装 ID；所有位都应保留为字符 0。 */
        val installId = NumericInstallIdGenerator.generate(random = zeroRandom, length = 6)

        assertEquals("000000", installId)
    }

    @Test
    /** 校验逻辑必须拒绝旧 UUID、空值和长度不符的数字串。 */
    fun isValidAcceptsOnlyDefaultLengthDigits() {
        assertTrue(NumericInstallIdGenerator.isValid("0".repeat(NumericInstallIdGenerator.DEFAULT_LENGTH)))
        assertTrue(NumericInstallIdGenerator.isValid("12345678901234567890"))
        assertTrue(!NumericInstallIdGenerator.isValid(null))
        assertTrue(!NumericInstallIdGenerator.isValid("123"))
        assertTrue(!NumericInstallIdGenerator.isValid("12345678-1234-1234-1234-123456789012"))
    }
}
