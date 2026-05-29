package com.cla.clip.base.general.config

import java.security.SecureRandom
import java.util.Random

/**
 * 安装级纯数字 ID 生成器。
 *
 * 该 ID 只用于本机安装身份、Shizuku 进程名和备份文件设备标签，不代表用户身份，也不应被数值化处理。
 */
object NumericInstallIdGenerator {
    /** 默认安装 ID 长度；20 位数字提供足够随机空间，同时保持进程名和备份标签短小。 */
    const val DEFAULT_LENGTH = 20

    /** 默认安全随机源；用于真实运行时生成不可预测的安装级随机数字串。 */
    private val secureRandom = SecureRandom()

    /**
     * 生成固定长度纯数字字符串。
     *
     * @param random 随机源；测试可传入固定 Random，生产默认使用 SecureRandom。
     * @param length 生成长度，必须为正数；调用方保存 String 以保留前导 0。
     */
    fun generate(
        random: Random = secureRandom,
        length: Int = DEFAULT_LENGTH,
    ): String {
        require(length > 0) { "length must be positive" }

        /** 数字字符构造器；逐位追加可保留前导 0。 */
        val builder = StringBuilder(length)
        repeat(length) {
            /** 当前位的随机数字，取值范围固定为 0..9。 */
            val digit = random.nextInt(10)
            builder.append(('0'.code + digit).toChar())
        }
        return builder.toString()
    }

    /**
     * 判断安装 ID 是否满足当前格式契约。
     *
     * @param value 待检查的持久化安装 ID；旧 UUID、空值或长度不符都视为无效。
     */
    fun isValid(value: String?): Boolean {
        return value?.length == DEFAULT_LENGTH && value.all { digit -> digit in '0'..'9' }
    }
}
