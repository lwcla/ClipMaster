package com.cla.clip.base.hidden.api

import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 隐藏 API 豁免统一入口。
 *
 * 该对象只负责 Android P/API 28 及以上的 HiddenApiBypass 调用和低版本跳过，具体豁免哪些签名仍由上层业务模块决定。
 */
object HiddenApiExemptions {
    /**
     * 在需要时添加隐藏 API 豁免。
     *
     * @param signatures 需要传给 HiddenApiBypass 的隐藏 API 签名前缀；为空时直接跳过，避免提交无意义调用。
     * @return true 表示已经实际调用 HiddenApiBypass；false 表示低版本或空签名被安全跳过。
     */
    fun addIfNeeded(vararg signatures: String): Boolean {
        if (signatures.isEmpty()) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false
        }
        HiddenApiBypass.addHiddenApiExemptions(*signatures)
        return true
    }

    /**
     * 判断当前系统版本是否需要提前豁免隐藏 API。
     *
     * @param apiLevel Android API level；Android P/API 28 起系统才启用隐藏 API 限制，低版本必须跳过豁免调用。
     */
    fun shouldAdd(apiLevel: Int): Boolean {
        return apiLevel >= Build.VERSION_CODES.P
    }
}
