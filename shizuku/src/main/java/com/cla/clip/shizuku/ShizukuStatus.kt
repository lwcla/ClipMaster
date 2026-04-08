package com.cla.clip.shizuku

sealed class ShizukuStatus {

    /** Shizuku 已连接 */
    data object Connected : ShizukuStatus()

    sealed class Disconnect : ShizukuStatus() {

        /** Shizuku 没有安装 */
        data object NotInstalled : Disconnect()

        /** 服务未存活 */
        data object ServiceNotAlive : Disconnect()

        /** 版本过低 （< 11） */
        data object VersionTooLow : Disconnect()

        /** 未授权，未知原因 */
        data object NotGranted : Disconnect()
    }
}