package com.cla.clip.master.localization

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

/** 捕获进入场景前的进程语言，返回仅恢复本进程资源与格式化配置的清理动作。 */
@Suppress("DEPRECATION")
internal fun captureProcessConfigurationRestorer(context: Context): () -> Unit {
    // 原始 Locale 可能包含独立显示/格式化类别，不能只恢复一个默认值。
    val defaultLocale = Locale.getDefault()
    val displayLocale = Locale.getDefault(Locale.Category.DISPLAY)
    val formatLocale = Locale.getDefault(Locale.Category.FORMAT)
    // 保存原语言列；公共 API 在默认语言不是首项时可能重排，当前验证边界见多语言主方案。
    val locales = LocaleList.getDefault()
    // 配置均复制为独立快照，后续 updateConfiguration 不得改变保存对象。
    val resourceConfigurations = listOf(context.resources, Resources.getSystem()).distinct().map { resources ->
        resources to Configuration(resources.configuration)
    }
    // JUnit 在场景成功或失败后都执行此动作，避免污染后续中文/回退及既有组件测试。
    return {
        for ((resources, configuration) in resourceConfigurations) {
            resources.updateConfiguration(configuration, resources.displayMetrics)
        }
        LocaleList.setDefault(locales)
        Locale.setDefault(defaultLocale)
        Locale.setDefault(Locale.Category.DISPLAY, displayLocale)
        Locale.setDefault(Locale.Category.FORMAT, formatLocale)
    }
}

/** 同步测试进程的 Java/ICU 和框架资源语言；不写设备设置，不影响其他应用。 */
@Suppress("DEPRECATION")
internal fun configureEnglishProcess(context: Context) {
    // Activity 启动可能重新同步设备 Locale；必须在页面挂载前再次设置，覆盖上下文外的相对时间格式化。
    val locales = LocaleList(Locale.US)
    Locale.setDefault(Locale.US)
    LocaleList.setDefault(locales)
    // 本进程应用与框架资源保持一致，保留真实设备密度；字体比例仍由各场景的 Context 驱动。
    for (resources in listOf(context.resources, Resources.getSystem())) {
        val configuration = Configuration(resources.configuration).apply { setLocales(locales) }
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }
    // Android Activity 配置同步可能分别更新显示和格式化类别，最后显式保持两者一致。
    Locale.setDefault(Locale.US)
    Locale.setDefault(Locale.Category.DISPLAY, Locale.US)
    Locale.setDefault(Locale.Category.FORMAT, Locale.US)
}
