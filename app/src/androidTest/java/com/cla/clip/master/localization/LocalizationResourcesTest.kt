package com.cla.clip.master.localization

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cla.clip.master.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.cla.clip.base.general.R as BaseR

/**
 * 验证宿主 APK 实际打包资源，覆盖英文默认、简体中文和未支持语言回退。
 * 宿主与基础模块始终检查；种子搜索仅在标题资源存在时检查，以兼容功能关闭的默认构建。
 * 每次只创建独立配置上下文，不修改设备语言、不启动 Activity、不读取或写入用户数据。
 */
@RunWith(AndroidJUnit4::class)
class LocalizationResourcesTest {
    /** 美式与英式英语都应读取英文，防止仅验证一个地区后遗漏默认资源回退。 */
    @Test
    fun englishRegionsUseEnglishAcrossModules() {
        // languageTag 分别代表两个常用英语地区，仅用于当前测试的配置副本。
        for (languageTag in listOf("en-US", "en-GB")) {
            assertEnglishStrings(contextFor(languageTag))
        }
    }

    /** 中国、新加坡和显式 Hans 脚本都应选择同一组简体中文资源。 */
    @Test
    fun simplifiedChineseRegionsAndScriptUseChineseAcrossModules() {
        // languageTag 覆盖地区推导脚本和直接指定脚本两种系统语言形式。
        for (languageTag in listOf("zh-CN", "zh-SG", "zh-Hans")) {
            assertSimplifiedChineseStrings(contextFor(languageTag))
        }
    }

    /** 未支持语言及繁体中文地区必须完整回退英文，不能泄漏依赖自带的局部翻译。 */
    @Test
    fun unsupportedAndTraditionalChineseLocalesFallBackToEnglish() {
        // languageTag 覆盖两个外语和两个繁体地区，验证最终 APK 的语言过滤边界。
        for (languageTag in listOf("fr-FR", "ja-JP", "zh-TW", "zh-HK")) {
            assertEnglishStrings(contextFor(languageTag))
        }
    }

    /** 多语言列表应按可支持语言的优先级匹配，而非固定取首项或总是选中文。 */
    @Test
    fun languageListSelectsFirstSupportedLocale() {
        assertSimplifiedChineseStrings(contextFor("fr-FR,zh-CN,en-US"))
        assertEnglishStrings(contextFor("fr-FR,en-GB,zh-CN"))
        assertEnglishStrings(contextFor("en-US,zh-CN"))
        assertSimplifiedChineseStrings(contextFor("zh-SG,en-US"))
    }

    /** 使用合成版本号和计数验证位置参数、字符串参数及百分号转义都能正常格式化。 */
    @Test
    fun formattedStringsKeepArgumentsAndLiteralPercent() {
        // english 与 chinese 是并存的独立配置上下文，用来比较两套文案的参数契约。
        val english = contextFor("en-US")
        val chinese = contextFor("zh-CN")
        assertEquals("2 / 7", english.getString(R.string.host_image_preview_position, 2, 7))
        assertEquals("2 / 7", chinese.getString(R.string.host_image_preview_position, 2, 7))
        assertEquals(
            "Current version: 1.2.3 (42)",
            english.getString(BaseR.string.base_general_app_update_current_version, "1.2.3", 42),
        )
        assertEquals(
            "当前版本：1.2.3（42）",
            chinese.getString(BaseR.string.base_general_app_update_current_version, "1.2.3", 42),
        )
        assertEquals("Downloading 37%", english.getString(BaseR.string.base_general_download_progress, 37))
        assertEquals("下载中 37%", chinese.getString(BaseR.string.base_general_download_progress, 37))
        assertEquals("Loading web page (37%)", english.getString(R.string.host_video_probe_loading, 37))
        assertEquals("网页加载中（37%）", chinese.getString(R.string.host_video_probe_loading, 37))
    }

    /** 模块已打包时中文零、一、多个均使用 other；默认未打包时明确跳过本模块专属测试。 */
    @Test
    fun bookmarkPluralsUseChineseOtherAndEnglishSingular() {
        // chinese 与 english 用真实 Resources 的数量选择规则验证打包后的 plurals。
        val chinese = contextFor("zh-Hans")
        val english = contextFor("en-US")
        // 仅用标题资源识别模块缺席；标题存在后，任何复数资源缺失都必须继续报错。
        assumeTrue(
            "种子搜索未打包：跳过模块专属复数验证，可用 -PenableMagnetFeature=true 运行完整检查",
            torrentSearchTitleId(english) != 0,
        )
        // chineseCountId 与 englishCountId 各自由对应上下文解析，避免依赖实现模块的 R 类。
        val chineseCountId = resourceId(chinese, "torrent_search_bookmarks_count_format", "plurals")
        val englishCountId = resourceId(english, "torrent_search_bookmarks_count_format", "plurals")
        // count 覆盖中文无单复数变化时最容易被错误 one 分支影响的边界。
        for (count in listOf(0, 1, 2)) {
            assertEquals(
                "中文书签数量 count=$count 应使用 other 文案",
                "$count 个书签",
                chinese.resources.getQuantityString(chineseCountId, count, count),
            )
        }
        assertEquals("0 bookmarks", english.resources.getQuantityString(englishCountId, 0, 0))
        assertEquals("1 bookmark", english.resources.getQuantityString(englishCountId, 1, 1))
        assertEquals("2 bookmarks", english.resources.getQuantityString(englishCountId, 2, 2))
    }

    /** 同进程切换配置副本后旧上下文仍保留自己的语言，目标上下文配置不被修改。 */
    @Test
    fun separateLocaleContextsDoNotCacheOrMutateEachOther() {
        // target 是被测应用原上下文，originalLanguageTags 记录本测试开始时的语言配置。
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val originalLanguageTags = target.resources.configuration.locales.toLanguageTags()
        // englishFirst、chinese 与 englishAgain 模拟连续创建不同语言上下文并保留旧引用。
        val englishFirst = contextFor("en-US")
        assertEnglishStrings(englishFirst)
        val chinese = contextFor("zh-CN")
        assertSimplifiedChineseStrings(chinese)
        val englishAgain = contextFor("en-GB")
        assertEnglishStrings(englishAgain)
        assertEnglishStrings(englishFirst)
        assertSimplifiedChineseStrings(chinese)
        assertEquals(
            "独立配置上下文不得修改被测应用原语言配置",
            originalLanguageTags,
            target.resources.configuration.locales.toLanguageTags(),
        )
    }

    /** 按 languageTags 创建资源配置副本；不调用 Locale.setDefault 或更新全局 Resources。 */
    private fun contextFor(
        /** 逗号分隔的 BCP 47 语言列表，顺序代表用户偏好。 */
        languageTags: String,
    ): Context {
        // target 提供被测 APK 的资源；configuration 仅修改其副本，防止污染其他测试。
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(target.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(languageTags))
        return target.createConfigurationContext(configuration)
    }

    /** 英文始终检查宿主与基础模块；可选 implementation 模块已打包时追加完整断言。 */
    private fun assertEnglishStrings(
        /** 当前待验证的独立语言上下文。 */
        context: Context,
    ) {
        // languageTags 仅用于断言诊断，不依赖设备当前语言或任何用户内容。
        val languageTags = context.resources.configuration.locales.toLanguageTags()
        assertEquals("$languageTags: host_image_preview_title", "Image preview", context.getString(R.string.host_image_preview_title))
        assertEquals("$languageTags: base_general_app_name", "ClipMaster", context.getString(BaseR.string.base_general_app_name))
        assertEquals("$languageTags: base_general_cancel", "Cancel", context.getString(BaseR.string.base_general_cancel))
        // titleId 为零只表示可选模块未打包，核心资源断言已执行，不跳过整个测试。
        val titleId = torrentSearchTitleId(context)
        if (titleId == 0) return
        assertEquals(
            "$languageTags: torrent_search_title",
            "Torrent Search",
            context.getString(titleId),
        )
        // 上游多语言包含书签标题，这一断言可发现依赖翻译未从最终 APK 排除的问题。
        assertEquals(
            "$languageTags: torrent_search_bookmarks_screen_title",
            "Bookmarks",
            context.getString(resourceId(context, "torrent_search_bookmarks_screen_title", "string")),
        )
    }

    /** 简体中文始终检查核心资源，模块存在时要求其同时匹配 Hans，防止局部英文回退。 */
    private fun assertSimplifiedChineseStrings(
        /** 当前待验证的独立语言上下文。 */
        context: Context,
    ) {
        // languageTags 标识失败的语言组合，方便定位脚本与地区匹配问题。
        val languageTags = context.resources.configuration.locales.toLanguageTags()
        assertEquals("$languageTags: host_image_preview_title", "图片预览", context.getString(R.string.host_image_preview_title))
        assertEquals("$languageTags: base_general_app_name", "剪贴板助手", context.getString(BaseR.string.base_general_app_name))
        assertEquals("$languageTags: base_general_cancel", "取消", context.getString(BaseR.string.base_general_cancel))
        // titleId 只负责判定可选模块是否存在，存在后的其他资源仍通过严格查询检查。
        val titleId = torrentSearchTitleId(context)
        if (titleId == 0) return
        assertEquals(
            "$languageTags: torrent_search_title",
            "种子搜索",
            context.getString(titleId),
        )
        assertEquals(
            "$languageTags: torrent_search_bookmarks_screen_title",
            "书签",
            context.getString(resourceId(context, "torrent_search_bookmarks_screen_title", "string")),
        )
    }

    /** 标题资源是可选 implementation 模块的唯一存在性判断；返回零允许默认构建不含该模块。 */
    @SuppressLint("DiscouragedApi")
    private fun torrentSearchTitleId(
        /** 提供合并 APK 资源表的独立语言上下文。 */
        context: Context,
    ): Int = context.resources.getIdentifier("torrent_search_title", "string", context.packageName)

    /** 可选 implementation 模块存在后按资源名严格查询，避免测试编译强制依赖功能实现模块。 */
    @SuppressLint("DiscouragedApi")
    private fun resourceId(
        /** 提供合并 APK 资源表和宿主包名的独立上下文。 */
        context: Context,
        /** 当前用例要验证的稳定资源键，禁止传入用户内容。 */
        name: String,
        /** Android 资源类型，本测试只使用 string 或 plurals。 */
        type: String,
    ): Int {
        // id 必须来自目标 APK；调用前已经确认模块存在，剩余资源缺失不能静默跳过。
        val id = context.resources.getIdentifier(name, type, context.packageName)
        assertTrue("种子搜索已打包，但被测 APK 缺少 $type/$name，请检查资源声明与打包配置", id != 0)
        return id
    }
}
