package com.cla.clip.shizuku

sealed class ShizukuStatus {

    /** Shizuku 已连接 */
    object Connected : ShizukuStatus()

    sealed class Disconnect : ShizukuStatus() {

        /** Shizuku 没有安装 */
        object NotInstalled : Disconnect()

        /** 服务未存活 */
        object ServiceNotAlive : Disconnect()

        /** 版本过低 （< 11） */
        object VersionTooLow : Disconnect()

        /** 未授权，未知原因 */
        object NotGranted : Disconnect()
    }
}