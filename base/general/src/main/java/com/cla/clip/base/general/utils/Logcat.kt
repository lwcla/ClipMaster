package com.cla.clip.base.general.utils

import android.util.Log
import com.cla.clip.base.general.BuildConfig

/** 应用统一日志 TAG，方便通过 adb logcat 或 setprop 按模块过滤。 */
private const val LOG_TAG = "cla_clip_master" // 定义自己的日志TAG

/**
 * # 打开日志输出
 * adb shell setprop log.tag.cla_clip_master DEBUG
 *
 * # 关闭日志输出，这里 INFO > DEBUG，会导致isLoggable返回false
 * adb shell setprop log.tag.cla_clip_master INFO
 *
 * 后续还是应该在release包就移除所有的日志打印
 */
/** 判断指定日志级别是否允许输出；Debug 包默认开启，Release 包可通过系统属性临时打开。 */
private fun loggable(level: Int) = BuildConfig.DEBUG || Log.isLoggable(LOG_TAG, level)

/** 输出 VERBOSE 级别日志，info 使用 lambda 避免关闭日志时仍拼接字符串。 */
fun logV(tag: String, info: () -> String) {
    if (loggable(Log.VERBOSE)) {
        Log.v(LOG_TAG, "$tag--> ${info()}")
    }
}

/** 输出 DEBUG 级别日志，适合开发调试和非敏感状态流转。 */
fun logD(tag: String, info: () -> String) {
    if (loggable(Log.DEBUG)) {
        Log.d(LOG_TAG, "$tag--> ${info()}")
    }
}

/** 输出 INFO 级别日志，适合记录重要但非异常的业务事件。 */
fun logI(tag: String, info: () -> String) {
    if (loggable(Log.INFO)) {
        Log.i(LOG_TAG, "$tag--> ${info()}")
    }
}

/** 输出 WARN 级别日志，可附带异常；用于可恢复但值得关注的问题。 */
fun logW(tag: String, tr: Throwable? = null, info: () -> String) {
    if (loggable(Log.WARN)) {
        Log.w(LOG_TAG, "$tag--> ${info()}", tr)
    }
}

/** 输出 ERROR 级别日志，可附带异常；用于失败路径和不可恢复问题。 */
fun logE(tag: String, tr: Throwable? = null, info: () -> String) {
    if (loggable(Log.ERROR)) {
        Log.e(LOG_TAG, "$tag--> ${info()}", tr)
    }
}

/** 输出 DEBUG 日志并附带当前调用栈，主要用于排查 Toast 等调用频率和来源。 */
fun logStack(tag: String, info: () -> String) {
    if (loggable(Log.DEBUG)) {
        Log.d(LOG_TAG, "$tag--> ${info()} \n${Log.getStackTraceString(Throwable())}")
    }
}
