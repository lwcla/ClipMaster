package com.cla.clip.shizuku

/**
 * Shizuku 用户服务进程名构造器。
 *
 * app 绑定参数、Provider 当前期望值和 Shizuku 进程自检必须复用同一格式，避免覆盖安装后新旧进程判断分叉。
 */
object ShizukuProcessName {
    /**
     * 构造传给 Shizuku SDK 的进程名后缀。
     *
     * @param version 当前 Shizuku 用户服务协议版本。
     * @param installId 当前安装级纯数字 ID。
     */
    fun buildSuffix(version: Int, installId: String): String {
        return "shizuku_${version}_${installId}"
    }

    /**
     * 构造 Android 进程完整名称。
     *
     * @param applicationId 宿主应用 id。
     * @param suffix Shizuku SDK processNameSuffix 使用的后缀。
     */
    fun buildFullName(applicationId: String, suffix: String): String {
        return "$applicationId:$suffix"
    }

    /**
     * 同时构造后缀和完整进程名。
     *
     * @param applicationId 宿主应用 id。
     * @param version 当前 Shizuku 用户服务协议版本。
     * @param installId 当前安装级纯数字 ID。
     */
    fun buildNames(
        applicationId: String,
        version: Int,
        installId: String,
    ): ShizukuProcessNames {
        /** Shizuku SDK 使用的进程名后缀。 */
        val suffix = buildSuffix(version = version, installId = installId)
        /** Android 系统中可观察到的完整进程名。 */
        val fullName = buildFullName(applicationId = applicationId, suffix = suffix)
        return ShizukuProcessNames(suffix = suffix, fullName = fullName)
    }
}

/**
 * Shizuku 用户服务进程名集合。
 *
 * @property suffix 传给 Shizuku SDK 的进程名后缀。
 * @property fullName Android 系统中的完整进程名。
 */
data class ShizukuProcessNames(
    val suffix: String,
    val fullName: String,
)
