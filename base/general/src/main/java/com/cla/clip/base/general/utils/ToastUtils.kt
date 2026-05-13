package com.cla.clip.base.general.utils

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.widget.Toast
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/** 当前正在展示的 Toast 弱引用，用于复用同一个 Toast 实例，避免短时间内连续弹出排队。 */
private var curToast: WeakReference<Toast>? = null

/** Toast 工具日志标签，用于排查跨线程展示或取消失败。 */
private const val TAG = "ToastUtils"

/**
 * 展示字符串资源 Toast。
 *
 * 可从任意协程线程调用，内部会切回主线程；资源 id 必须是用户可见文案。
 */
suspend fun Context.toast(@StringRes res: Int, duration: Int = Toast.LENGTH_SHORT) {
    val text = getString(res)
    toast(text, duration)
}

/**
 * 展示文本 Toast，并复用当前 Toast 实例减少重复排队。
 *
 * 空字符串或字符串 "null" 会被忽略；可从后台线程调用，真正 show 时会切到主线程。
 * @param msg 需要提示的信息
 * @param duration toast显示的时长
 */
@SuppressLint("ShowToast")
suspend fun Context.toast(
    msg: String?,
    duration: Int = Toast.LENGTH_SHORT,
) {
    if (msg.isNullOrEmpty() || "null".equals(msg, ignoreCase = true)) {
        return
    }

    logStack(TAG) { "duration=$duration msg=$msg" }

    /** 真正创建/复用并展示 Toast 的主线程逻辑。 */
    fun toast() {
        runCatching {
            val toast = curToast?.get() ?: Toast.makeText(this, msg, duration)
            toast?.setGravity(Gravity.CENTER, 0, 0)
            toast?.setText(msg)
            toast.duration = duration
            curToast = WeakReference(toast)
            toast.show()
        }.getOrElse {
            logE(TAG, it) { "singleToast 出错" }
        }
    }

    if (isMainThread()) {
        toast()
    } else {
        withContext(Dispatchers.Main) {
            toast()
        }
    }
}

/**
 * 取消当前正在展示的 Toast。
 *
 * 可从任意线程调用；通常在页面退出或新提示展示前使用，避免旧 Toast 残留影响用户判断。
 */
suspend fun Context.cancelToast() {
    /** 真正执行 Toast 取消的主线程逻辑。 */
    fun cancel() {
        val toast = curToast?.get()
        toast?.cancel()
    }

    if (isMainThread()) {
        cancel()
    } else {
        withContext(Dispatchers.Main) {
            cancel()
        }
    }
}
