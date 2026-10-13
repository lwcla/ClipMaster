package com.cla.clip.base.general.utils

import android.util.Log
import com.cla.clip.base.general.BuildConfig

/** 应用统一日志 TAG，方便通过 adb logcat 或 setprop 按模块过滤。 */
private const val LOG_TAG = "cla_clip_master" // 定义自己的日志TAG

/** 本机用户明确开启保存时放行诊断；只改变输出许可，不执行文件 IO。 */
@Volatile private var captureDiagnosticsEnabled = false

/** 由主进程采集控制器设置会话许可，停止时恢复原系统级别门控。 */
fun setLogCaptureDiagnosticsEnabled(enabled: Boolean) { captureDiagnosticsEnabled = enabled }

/** 网络拦截器可查询采集许可，避免开发 BASIC 日志输出完整请求地址到公开文件。 */
fun isLogCaptureDiagnosticsEnabled(): Boolean = captureDiagnosticsEnabled

/**
 * # 为正式包打开 DEBUG 及更高级别输出
 * adb shell setprop log.tag.cla_clip_master DEBUG
 *
 * # 正式包只保留 INFO 及更高级别，关闭 VERBOSE/DEBUG
 * adb shell setprop log.tag.cla_clip_master INFO
 *
 * Debug 包始终允许日志；正式包遵循系统许可或用户本次采集许可，关闭采集后恢复系统门控。
 * 此方法只判断即时 Logcat 输出，不启动日志采集或文件保存。
 */
private fun loggable(level: Int) = BuildConfig.DEBUG || captureDiagnosticsEnabled || Log.isLoggable(LOG_TAG, level)

/** 只在用户开启保存时过滤旧诊断里的地址令牌，调用方仍需避免输出业务正文。 */
private fun captureSafeMessage(info: () -> String): String = if (captureDiagnosticsEnabled) formatLogMessage(info()) else info()

/** 输出 VERBOSE 级别日志，info 使用 lambda 避免关闭日志时仍拼接字符串。 */
fun logV(tag: String, info: () -> String) {
    if (loggable(Log.VERBOSE)) {
        Log.v(LOG_TAG, "$tag--> ${captureSafeMessage(info)}")
    }
}

/** 输出 DEBUG 级别日志，适合开发调试和非敏感状态流转。 */
fun logD(tag: String, info: () -> String) {
    if (loggable(Log.DEBUG)) {
        Log.d(LOG_TAG, "$tag--> ${captureSafeMessage(info)}")
    }
}

/** 输出 INFO 级别日志，适合记录重要但非异常的业务事件。 */
fun logI(tag: String, info: () -> String) {
    if (loggable(Log.INFO)) {
        Log.i(LOG_TAG, "$tag--> ${captureSafeMessage(info)}")
    }
}

/** 输出 WARN 级别日志，可附带异常；用于可恢复但值得关注的问题。 */
fun logW(tag: String, tr: Throwable? = null, info: () -> String) {
    if (loggable(Log.WARN)) {
        // 保存开启期间保留代码栈但丢弃异常原文，避免 URL/凭据通过 Throwable 被写到 Download。
        if (captureDiagnosticsEnabled && tr != null) Log.w(LOG_TAG, "$tag--> ${captureSafeMessage(info)}\n${formatLogThrowable(tr)}")
        else Log.w(LOG_TAG, "$tag--> ${captureSafeMessage(info)}", tr)
    }
}

/** 输出 ERROR 级别日志，可附带异常；用于失败路径和不可恢复问题。 */
fun logE(tag: String, tr: Throwable? = null, info: () -> String) {
    if (loggable(Log.ERROR)) {
        // 与 WARN 使用同一脱敏契约；关闭保存后沿用原有即时 Logcat 行为。
        if (captureDiagnosticsEnabled && tr != null) Log.e(LOG_TAG, "$tag--> ${captureSafeMessage(info)}\n${formatLogThrowable(tr)}")
        else Log.e(LOG_TAG, "$tag--> ${captureSafeMessage(info)}", tr)
    }
}

/** 输出 DEBUG 日志并附带当前调用栈，主要用于排查 Toast 等调用频率和来源。 */
fun logStack(tag: String, info: () -> String) {
    if (loggable(Log.DEBUG)) {
        Log.d(LOG_TAG, "$tag--> ${captureSafeMessage(info)} \n${Log.getStackTraceString(Throwable())}")
    }
}
