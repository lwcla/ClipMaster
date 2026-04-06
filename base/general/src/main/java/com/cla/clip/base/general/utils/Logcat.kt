package com.cla.clip.base.general.utils

import android.util.Log
import com.cla.clip.base.general.BuildConfig

const val LOG_TAG = "cla_clip_master" // 定义自己的日志TAG

val LOG_DEBUG get() = BuildConfig.DEBUG || Log.isLoggable(LOG_TAG, Log.INFO) // 日志开关

inline fun logV(tag: String, info: () -> String) {
    if (LOG_DEBUG) {
        Log.v(LOG_TAG, "$tag--> ${info()}")
    }
}

inline fun logI(tag: String, info: () -> String) {
    if (LOG_DEBUG) {
        Log.i(LOG_TAG, "$tag--> ${info()}")
    }
}

inline fun logD(tag: String, info: () -> String) {
    if (LOG_DEBUG) {
        Log.d(LOG_TAG, "$tag--> ${info()}")
    }
}

inline fun logW(tag: String, tr: Throwable? = null, info: () -> String) {
    if (LOG_DEBUG) {
        Log.w(LOG_TAG, "$tag--> ${info()}", tr)
    }
}

inline fun logE(tag: String, tr: Throwable? = null, info: () -> String) {
    if (LOG_DEBUG) {
        Log.e(LOG_TAG, "$tag--> ${info()}", tr)
    }
}

inline fun logStack(tag: String, info: () -> String) {
    if (LOG_DEBUG) {
        Log.d(LOG_TAG, "$tag--> ${info()} \n${Log.getStackTraceString(Throwable())}")
    }
}