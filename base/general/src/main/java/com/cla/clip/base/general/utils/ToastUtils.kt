package com.cla.clip.base.general.utils

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.widget.Toast
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

private var curToast: WeakReference<Toast>? = null
private const val TAG = "ToastUtils"

suspend fun Context.toast(@StringRes res: Int, duration: Int = Toast.LENGTH_SHORT) {
    val text = getString(res)
    toast(text, duration)
}

/**
 * 使用同一个tag的toast不会重复弹出来
 * @param msg 需要提示的信息
 * @param duration toast显示的时长
 * @param time 是否需要增加时间限制，10s秒钟只显示一次
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
 * 取消当前页面的toast
 */
suspend fun Context.cancelToast() {
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