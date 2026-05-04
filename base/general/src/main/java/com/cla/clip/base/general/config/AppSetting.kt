package com.cla.clip.base.general.config

import com.cla.clip.base.general.utils.logE
import com.tencent.mmkv.MMKV
import java.util.UUID

object AppSetting {

    private const val TAG = "AppSetting"

    private val mmkv by lazy { MMKV.defaultMMKV() }

    /** 视频下载任务 ID，值为 -1 表示没有正在下载的任务 */
    private const val KEY_VIDEO_DOWNLOAD_TASK_ID = "video_download_task_id"
    var videoDownloadTaskId
        get() = mmkv.getLong(KEY_VIDEO_DOWNLOAD_TASK_ID, -1L)
        set(value) {
            mmkv.putLong(KEY_VIDEO_DOWNLOAD_TASK_ID, value)
        }

    /**
     * app安装之后生成的一个唯一值，只要app不被卸载，这个值就不会变化
     * 但app卸载之后重新安装，这个值会变化
     * 这个值可以用来区分不同的安装，或者在app被卸载之后重新安装时，识别出这是一个新的安装
     * 这个值不包含任何个人隐私信息，也不会被用来追踪用户，只是一个随机生成的UUID
     */
    private const val KEY_PID = "pid"
    val pid: String
        get() {
            var value = mmkv.getString(KEY_PID, null)
            if (value.isNullOrBlank()) {
                logE(TAG) { "创建pid" }
                val uuid = UUID.randomUUID().toString()
                mmkv.putString(KEY_PID, uuid)
                value = uuid
            }

            return value
        }


    /**
     * shizuku进程名
     * 用来在shizuku进程启动之后，检查当前是否为最新的shizuku进程，清理掉旧的shizuku进程
     */
    private const val KEY_SHIZUKU_SUFFIX = "current_suffix"
    var shizukuSuffix: String
        get() = mmkv.getString(KEY_SHIZUKU_SUFFIX, null)?.removePrefix(":")?.takeIf { it.isNotBlank() } ?: ""
        set(value) {
            // current suffix 只由 App 进程写入。Shizuku 进程只能通过 Provider/callback
            // 读取，不能反写，避免旧 Shizuku 进程把新 suffix 覆盖回旧值。
            mmkv.putString(KEY_SHIZUKU_SUFFIX, value.removePrefix(":"))
        }

}