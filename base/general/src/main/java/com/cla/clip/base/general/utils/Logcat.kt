package com.cla.clip.base.general.utils

import android.util.Log
import com.cla.clip.base.general.BuildConfig

const val LOG_TAG = "cla_clip_master" // 定义自己的日志TAG

fun loggable(level: Int) = BuildConfig.DEBUG || Log.isLoggable(LOG_TAG, level)

inline fun logV(tag: String, info: () -> String) {
    if (loggable(Log.VERBOSE)) {
        Log.v(LOG_TAG, "$tag--> ${info()}")
    }
}

inline fun logI(tag: String, info: () -> String) {
    if (loggable(Log.INFO)) {
        Log.i(LOG_TAG, "$tag--> ${info()}")
    }
}

inline fun logD(tag: String, info: () -> String) {
    if (loggable(Log.DEBUG)) {
        Log.d(LOG_TAG, "$tag--> ${info()}")
    }
}

inline fun logW(tag: String, tr: Throwable? = null, info: () -> String) {
    if (loggable(Log.WARN)) {
        Log.w(LOG_TAG, "$tag--> ${info()}", tr)
    }
}

inline fun logE(tag: String, tr: Throwable? = null, info: () -> String) {
    if (loggable(Log.ERROR)) {
        Log.e(LOG_TAG, "$tag--> ${info()}", tr)
    }
}

inline fun logStack(tag: String, info: () -> String) {
    if (loggable(Log.DEBUG)) {
        Log.d(LOG_TAG, "$tag--> ${info()} \n${Log.getStackTraceString(Throwable())}")
    }
}