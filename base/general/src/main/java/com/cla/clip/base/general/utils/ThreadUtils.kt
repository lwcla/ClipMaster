package com.cla.clip.base.general.utils

import android.os.Looper

/** 判断当前是否在主线程 */
fun isMainThread() = Looper.myLooper() == Looper.getMainLooper()