package com.cla.clip.base.general.config

import com.cla.clip.base.general.utils.logE
import com.tencent.mmkv.MMKV
import java.util.UUID

/**
 * 应用级轻量配置中心。
 *
 * 基于 MMKV 保存跨进程或跨启动需要读取的小型状态；这里不存放大对象和用户可见内容，避免配置文件膨胀或隐私边界不清。
 */
object AppSetting {

    /** 日志标签，用于排查配置初始化和持久化写入。 */
    private const val TAG = "AppSetting"

    /** 默认 MMKV 实例，首次访问时初始化；调用方需要确保 BaseApplication 已在主进程初始化 MMKV。 */
    private val mmkv by lazy { MMKV.defaultMMKV() }

    /** 视频下载任务 ID，值为 -1 表示没有正在下载的任务 */
    private const val KEY_VIDEO_DOWNLOAD_TASK_ID = "video_download_task_id"

    /** 最近一次视频下载任务 id，-1 表示没有记录；用于启动新下载前清理上次未完成的 pending 输出。 */
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

    /** 当前安装实例的随机 UUID，不包含用户身份信息；卸载重装后会变化。 */
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

    /** 当前有效的 Shizuku 进程后缀，不包含冒号；用于识别和清理旧 Shizuku 进程。 */
    var shizukuSuffix: String
        get() = mmkv.getString(KEY_SHIZUKU_SUFFIX, null)?.removePrefix(":")?.takeIf { it.isNotBlank() } ?: ""
        set(value) {
            // current suffix 只由 App 进程写入。Shizuku 进程只能通过 Provider/callback
            // 读取，不能反写，避免旧 Shizuku 进程把新 suffix 覆盖回旧值。
            mmkv.putString(KEY_SHIZUKU_SUFFIX, value.removePrefix(":"))
        }

    /** 权限说明是否已经展开，保存用户在“我的”页的折叠偏好。 */
    private const val KEY_PERMISSION_EXPANDED = "permission_expanded"

    /** “权限说明”卡片展开状态；只影响 UI 展示，不参与权限判断。 */
    var permissionExpanded: Boolean
        get() = mmkv.getBoolean(KEY_PERMISSION_EXPANDED, false)
        set(value) {
            mmkv.putBoolean(KEY_PERMISSION_EXPANDED, value)
        }

    /** 回收站默认保留天数，单位天；默认 30 天，和产品默认自动清理策略保持一致。 */
    const val DEFAULT_RECYCLE_BIN_RETENTION_DAYS = 30

    /** 回收站保留天数最小值，避免保存 0 或负数导致所有回收站数据立即过期。 */
    const val MIN_RECYCLE_BIN_RETENTION_DAYS = 1

    /** 回收站保留天数最大值，限制自定义输入范围，避免毫秒换算溢出或产生不合理承诺。 */
    const val MAX_RECYCLE_BIN_RETENTION_DAYS = 3650

    /** 回收站保留天数配置 key；值按滚动 24 小时窗口解释，不按自然日零点截断。 */
    private const val KEY_RECYCLE_BIN_RETENTION_DAYS = "recycle_bin_retention_days"

    /** 回收站保留天数；保存时会裁剪到 1 到 3650 天之间。 */
    var recycleBinRetentionDays: Int
        get() = mmkv.getInt(KEY_RECYCLE_BIN_RETENTION_DAYS, DEFAULT_RECYCLE_BIN_RETENTION_DAYS)
            .coerceIn(MIN_RECYCLE_BIN_RETENTION_DAYS, MAX_RECYCLE_BIN_RETENTION_DAYS)
        set(value) {
            mmkv.putInt(
                KEY_RECYCLE_BIN_RETENTION_DAYS,
                value.coerceIn(MIN_RECYCLE_BIN_RETENTION_DAYS, MAX_RECYCLE_BIN_RETENTION_DAYS)
            )
        }
}
