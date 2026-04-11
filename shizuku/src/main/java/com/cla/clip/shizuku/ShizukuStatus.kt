package com.cla.clip.shizuku

import androidx.annotation.StringRes
import com.cla.clip.base.general.R

sealed class ShizukuStatus(@StringRes open val textRes: Int) {

    /** Shizuku 已连接 */
    data class Connected(override val textRes: Int = R.string.base_general_service_is_running) : ShizukuStatus(textRes)

    sealed class Disconnect(override val textRes: Int) : ShizukuStatus(textRes) {

        /** Shizuku 没有安装 */
        data class NotInstalled(override val textRes: Int = R.string.base_general_shizuku_not_install) : Disconnect(textRes)

        /** 服务未存活 */
        data class ServiceNotAlive(override val textRes: Int = R.string.base_general_shizuku_service_not_alive) : Disconnect(textRes)

        /** 版本过低 （< 11） */
        data class VersionTooLow(override val textRes: Int = R.string.base_general_shizuku_version_too_low) : Disconnect(textRes)

        /** 未授权，未知原因 */
        data class NotGranted(override val textRes: Int = R.string.base_general_shizuku_not_granted) : Disconnect(textRes)
    }
}