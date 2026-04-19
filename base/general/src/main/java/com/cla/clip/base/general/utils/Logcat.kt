package com.cla.clip.base.general.utils

import android.util.Log
import com.cla.clip.base.general.BuildConfig

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
private fun loggable(level: Int) = BuildConfig.DEBUG || Log.isLoggable(LOG_TAG, level)

fun logV(tag: String, info: () -> String) {
    if (loggable(Log.VERBOSE)) {
        Log.v(LOG_TAG, "$tag--> ${info()}")
    }
}

fun logD(tag: String, info: () -> String) {
    if (loggable(Log.DEBUG)) {
        Log.d(LOG_TAG, "$tag--> ${info()}")
    }
}

fun logI(tag: String, info: () -> String) {
    if (loggable(Log.INFO)) {
        Log.i(LOG_TAG, "$tag--> ${info()}")
    }
}

fun logW(tag: String, tr: Throwable? = null, info: () -> String) {
    if (loggable(Log.WARN)) {
        Log.w(LOG_TAG, "$tag--> ${info()}", tr)
    }
}

fun logE(tag: String, tr: Throwable? = null, info: () -> String) {
    if (loggable(Log.ERROR)) {
        Log.e(LOG_TAG, "$tag--> ${info()}", tr)
    }
}

fun logStack(tag: String, info: () -> String) {
    if (loggable(Log.DEBUG)) {
        Log.d(LOG_TAG, "$tag--> ${info()} \n${Log.getStackTraceString(Throwable())}")
    }
}