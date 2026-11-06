package com.cla.clip.feature.torrent.search.localization

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

/** 捕获场景进入前的完整进程语言；返回清理动作，成功和失败用例都必须还原。 */
@Suppress("DEPRECATION")
internal fun captureTorrentProcessConfigurationRestorer(context: Context): () -> Unit {
    // defaultLocale、displayLocale、formatLocale 分别保存通用、界面和格式化类别，不能只恢复首个默认值。
    val defaultLocale = Locale.getDefault()
    val displayLocale = Locale.getDefault(Locale.Category.DISPLAY)
    val formatLocale = Locale.getDefault(Locale.Category.FORMAT)
    // locales 保留完整资源匹配优先级，避免英文场景污染后续中文或回退测试。
    val locales = LocaleList.getDefault()
    // resourceConfigurations 将应用/框架资源配置复制为快照，后续覆盖不能修改保存对象。
    val resourceConfigurations = listOf(context.resources, Resources.getSystem()).distinct().map { resources ->
        resources to Configuration(resources.configuration)
    }
    // 清理只还原本测试进程；不写系统设置，也不改变其他应用的语言或字号。
    return {
        // resources 与 configuration 对应进入场景时同一个资源实例和独立配置快照。
        for ((resources, configuration) in resourceConfigurations) {
            resources.updateConfiguration(configuration, resources.displayMetrics)
        }
        LocaleList.setDefault(locales)
        Locale.setDefault(defaultLocale)
        Locale.setDefault(Locale.Category.DISPLAY, displayLocale)
        Locale.setDefault(Locale.Category.FORMAT, formatLocale)
    }
}

/** 对齐独立测试进程的 Java/ICU 与框架资源；模块不能依赖 app 测试源集，因此保留这份最小平台适配。 */
@Suppress("DEPRECATION")
internal fun configureTorrentEnglishProcess(context: Context) {
    // locales 在每场景 Activity 启动后重设，覆盖启动时恢复设备语言导致 DateFormat 仍为中文的情况。
    val locales = LocaleList(Locale.US)
    Locale.setDefault(Locale.US)
    LocaleList.setDefault(locales)
    // resources 分别是本测试应用及当前进程的框架资源，不更改设备设置或其他应用语言。
    for (resources in listOf(context.resources, Resources.getSystem())) {
        // configuration 只改语言，保留设备真实密度；各字号仍由页面独立 Context 驱动。
        val configuration = Configuration(resources.configuration).apply { setLocales(locales) }
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }
    Locale.setDefault(Locale.US)
    Locale.setDefault(Locale.Category.DISPLAY, Locale.US)
    Locale.setDefault(Locale.Category.FORMAT, Locale.US)
}
