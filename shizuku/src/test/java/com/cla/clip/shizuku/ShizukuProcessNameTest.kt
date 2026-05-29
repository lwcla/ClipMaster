package com.cla.clip.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test

/** Shizuku 进程名构造测试，确保 app、Provider 和 Shizuku 侧身份协议使用同一格式。 */
class ShizukuProcessNameTest {
    @Test
    /** suffix 只包含固定前缀、服务版本和安装 ID，不包含应用包名或冒号。 */
    fun buildSuffixUsesVersionAndInstallId() {
        /** 构造出的进程后缀；该值传给 Shizuku SDK 的 processNameSuffix。 */
        val suffix = ShizukuProcessName.buildSuffix(version = 9, installId = "001234")

        assertEquals("shizuku_9_001234", suffix)
    }

    @Test
    /** 完整进程名必须由 applicationId、冒号和 suffix 构成，用于旧进程自检。 */
    fun buildFullNameUsesApplicationIdAndSuffix() {
        /** 构造出的完整进程名；Provider 返回和 Shizuku 自身进程名都按它比较。 */
        val fullName = ShizukuProcessName.buildFullName(
            applicationId = "com.cla.clip.master",
            suffix = "shizuku_9_001234"
        )

        assertEquals("com.cla.clip.master:shizuku_9_001234", fullName)
    }

    @Test
    /** 组合构造方法必须同时返回同源 suffix 和完整进程名，避免调用方各自拼接。 */
    fun buildNamesReturnsConsistentSuffixAndFullName() {
        /** 构造出的进程名集合；app 绑定和 Provider 查询都应复用它。 */
        val names = ShizukuProcessName.buildNames(
            applicationId = "com.cla.clip.master",
            version = 9,
            installId = "001234"
        )

        assertEquals("shizuku_9_001234", names.suffix)
        assertEquals("com.cla.clip.master:shizuku_9_001234", names.fullName)
    }
}
