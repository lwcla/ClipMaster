package com.cla.clip.base.general.utils

import android.icu.text.RelativeDateTimeFormatter
import android.text.format.DateUtils
import android.text.format.DateUtils.SECOND_IN_MILLIS

/**
 *  将一个时间戳转换为相对时间字符串，例如 "5分钟前"、"昨天" 等。
 *  这个函数首先计算当前时间与给定时间的差值，如果差值小于1秒钟，则直接返回 ICU 标准的 "现在" (Now)。
 *  如果差值超过1秒钟，则使用 Android 的 DateUtils 来生成相对时间字符串，这样可以处理更长时间的差值，例如分钟、小时、天等。
 */
fun Long.toRelativeTimeSpanString(): String {
    val time = this
    // 3. 时间格式化
    val now = System.currentTimeMillis()
    val diff = now - time
    return if (diff < 1000) {
        // 直接返回 ICU 标准的 "现在" (Now)
        RelativeDateTimeFormatter.getInstance()
            .format(RelativeDateTimeFormatter.Direction.PLAIN, RelativeDateTimeFormatter.AbsoluteUnit.NOW)
            .toString()
    } else {
        // 超过1分钟还是用 DateUtils 处理比较方便
        DateUtils.getRelativeTimeSpanString(time, now, SECOND_IN_MILLIS).toString()
    }
}