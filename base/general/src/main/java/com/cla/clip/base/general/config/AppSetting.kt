package com.cla.clip.base.general.config

import android.content.Context
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.tencent.mmkv.MMKV
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppSetting @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AppSetting"

        /** 视频下载任务 ID，值为 -1 表示没有正在下载的任务 */
        private const val KEY_VIDEO_DOWNLOAD_TASK_ID = "video_download_task_id"

        private const val KEY_PID = "pid"
    }

    init {
        val rootDir = MMKV.initialize(context)
        logD(TAG) { "onCreate : mmkv root: $rootDir" }
    }

    private val mmkv by lazy { MMKV.defaultMMKV() }

    /** 视频下载任务 ID，值为 -1 表示没有正在下载的任务 */
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
}