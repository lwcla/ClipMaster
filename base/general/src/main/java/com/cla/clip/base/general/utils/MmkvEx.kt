package com.cla.clip.base.general.utils

import com.cla.clip.base.general.utils.Cache.Companion.KEY_VIDEO_DOWNLOAD_TASK_ID
import com.cla.clip.base.general.utils.MmkvEx.mmkv
import com.tencent.mmkv.MMKV


object MmkvEx {
    val mmkv by lazy { MMKV.defaultMMKV() }
}

/** 视频下载任务 ID，值为 -1 表示没有正在下载的任务 */
var videoDownloadTaskId
    get() = Cache(KEY_VIDEO_DOWNLOAD_TASK_ID).getLong(-1L)
    set(value) {
        Cache(KEY_VIDEO_DOWNLOAD_TASK_ID).putLong(value)
    }

@JvmInline
value class Cache(val key: String) {

    companion object {

        /** 视频下载任务 ID，值为 -1 表示没有正在下载的任务 */
        const val KEY_VIDEO_DOWNLOAD_TASK_ID = "video_download_task_id"
    }

    fun getString(defaultValue: String? = null): String? {
        return mmkv.getString(key, defaultValue)
    }

    fun putString(value: String?) {
        mmkv.putString(key, value)
    }

    fun getInt(defaultValue: Int = 0): Int {
        return mmkv.getInt(key, defaultValue)
    }

    fun putInt(value: Int) {
        mmkv.putInt(key, value)
    }

    fun getBoolean(defaultValue: Boolean = false): Boolean {
        return mmkv.getBoolean(key, defaultValue)
    }

    fun putBoolean(value: Boolean) {
        mmkv.putBoolean(key, value)
    }

    fun getLong(defaultValue: Long = 0L): Long {
        return mmkv.getLong(key, defaultValue)
    }

    fun putLong(value: Long) {
        mmkv.putLong(key, value)
    }

    fun getFloat(defaultValue: Float = 0f): Float {
        return mmkv.getFloat(key, defaultValue)
    }

    fun putFloat(value: Float) {
        mmkv.putFloat(key, value)
    }
}