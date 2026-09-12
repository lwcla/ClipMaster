package com.cla.clip.master

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * API 24/36 共用的宿主与 TorrentSearch 合并 Manifest 冒烟测试。
 *
 * 测试不联网、不读取用户数据；功能开关关闭时验证入口完全消失，开启时验证真实 Activity 与三个外部 alias 的导出边界。
 */
@RunWith(AndroidJUnit4::class)
class TorrentSearchReleaseGateInstrumentedTest {
    /** 宿主 applicationId 在所有设备和功能开关组合中保持稳定。 */
    @Test
    fun appContextKeepsStablePackageName() {
        /** appContext 是当前被测 APK 的目标上下文。 */
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.cla.clip.master", appContext.packageName)
    }

    /** 功能开启时只导出三个受限 alias，关闭时实现 Activity 与 alias 必须一起从 APK 消失。 */
    @Test
    fun torrentSearchManifestMatchesFeatureClasspath() {
        /** appContext 是当前被测 APK 的目标上下文。 */
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        /** packageInfo 包含合并后的 Activity 与 alias 声明。 */
        @Suppress("DEPRECATION")
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, PackageManager.GET_ACTIVITIES)
        /** activitiesByName 按完整类名索引合并 Manifest 中的 ActivityInfo。 */
        val activitiesByName = packageInfo.activities.orEmpty().associateBy { /** activityInfo 是当前合并组件。 */ activityInfo -> activityInfo.name }
        /** featureClassPresent 由最终 APK classpath 判断 TorrentSearch 实现是否已编译进当前变体。 */
        val featureClassPresent = runCatching { Class.forName(TORRENT_SEARCH_ACTIVITY) }.isSuccess
        /** implementationInfo 是不应对外导出的真实 Activity。 */
        val implementationInfo = activitiesByName[TORRENT_SEARCH_ACTIVITY]

        if (featureClassPresent) {
            assertNotNull(implementationInfo)
            assertFalse(implementationInfo!!.exported)
            /** aliasName 是每个允许接收外部文本动作的稳定 alias。 */
            TORRENT_SEARCH_ALIASES.forEach { aliasName ->
                /** aliasInfo 是合并 Manifest 中当前外部入口。 */
                val aliasInfo = activitiesByName[aliasName]
                assertNotNull(aliasInfo)
                assertTrue(aliasInfo!!.exported)
                assertEquals(TORRENT_SEARCH_ACTIVITY, aliasInfo.targetActivity)
            }
            /** cleartextEnabled 验证用户确认的真实 HTTP 可用性进入最终安装包。 */
            val cleartextEnabled = packageInfo.applicationInfo!!.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0
            assertTrue(cleartextEnabled)
        } else {
            assertNull(implementationInfo)
            /** aliasName 是功能关闭时必须从合并 Manifest 移除的外部入口。 */
            TORRENT_SEARCH_ALIASES.forEach { aliasName -> assertNull(activitiesByName[aliasName]) }
        }
    }

    /** 被测组件类名集中定义，避免断言和 Manifest 稳定协议漂移。 */
    companion object {
        /** TorrentSearch 实现 Activity 的最终完整类名。 */
        private const val TORRENT_SEARCH_ACTIVITY = "com.cla.clip.feature.torrent.search.TorrentSearchActivity"
        /** 三个用户授权的外部文本入口；真实 Activity 本身保持非导出。 */
        private val TORRENT_SEARCH_ALIASES = listOf(
            "com.cla.clip.feature.torrent.search.TorrentSearchActionSearchAlias",
            "com.cla.clip.feature.torrent.search.TorrentSearchSendAlias",
            "com.cla.clip.feature.torrent.search.TorrentSearchProcessTextAlias",
        )
    }
}
