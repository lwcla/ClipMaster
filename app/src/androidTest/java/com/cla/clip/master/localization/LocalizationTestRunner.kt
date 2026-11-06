package com.cla.clip.master.localization

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** 资源专用 runner 使用普通 Application，避免验证翻译时启动后台任务或 Shizuku 连接。 */
class LocalizationTestRunner : AndroidJUnitRunner() {
    /** 只替换测试进程的 Application；资源仍来自实际构建的 APK，不修改设备系统语言。 */
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, Application::class.java.name, context)
}
