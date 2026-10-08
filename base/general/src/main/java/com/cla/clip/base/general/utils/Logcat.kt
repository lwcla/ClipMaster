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
 * 开发阶段自动录制开启时所有级别都会输出，正式公开发布前另评估关闭策略。
 */
/** 开发录制是进程内状态，不持久化为设置或进入备份。 */
@Volatile private var developmentRecording = false

/** Application 开启开发录制，使正式包也有 V/D 阶段证据，不需要 ADB setprop。 */
fun enableDevelopmentLogRecording() { developmentRecording = true }

/** 开发录制覆盖全部级别，其他调用场景保留原有 Debug/系统属性规则。 */
private fun loggable(level: Int) = developmentRecording || BuildConfig.DEBUG || Log.isLoggable(LOG_TAG, level)

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
