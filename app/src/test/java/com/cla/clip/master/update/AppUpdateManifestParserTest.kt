package com.cla.clip.master.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AppUpdateManifestParser 协议校验与下载源排序测试。 */
class AppUpdateManifestParserTest {
    /** 被测解析器实例。 */
    private val parser = AppUpdateManifestParser()
    /** 当前安装包上下文；大部分测试都基于它做版本和渠道比较。 */
    private val currentApp = AppUpdateCurrentApp(
        packageName = "com.cla.clip.master",
        versionCode = 21,
        versionName = "0.4.0",
        channel = "internal",
    )

    @Test
    /** 国内推荐下载源应排在前面，同时当前版本低于最低支持版本时应判定为强更。 */
    fun parseReturnsUpdateAndSortsChinaRecommendedDownloadFirst() {
        /** 默认测试 manifest 解析出的结果。 */
        val result = parser.parse(updateJson(), currentApp)

        assertTrue(result is AppUpdateCheckResult.UpdateAvailable)
        /** 解析后的更新信息；后续断言版本、强更和下载源顺序都从这里读取。 */
        val info = (result as AppUpdateCheckResult.UpdateAvailable).info
        assertEquals(22, info.versionCode)
        assertEquals("0.4.1", info.versionName)
        assertEquals(true, info.forceUpdate)
        assertEquals("gitee", info.downloads.first().id)
        assertEquals("github", info.downloads.last().id)
        assertEquals("Gitee Release", info.fallbackReleasePage?.name)
    }

    @Test
    /** Gitee release 资产里应只挑出名为 update.json 的附件地址。 */
    fun extractGiteeUpdateJsonUrlReturnsReleaseAssetDownloadUrl() {
        /** 从 Gitee release JSON 中提取出的 update.json 附件地址。 */
        val url = parser.extractGiteeUpdateJsonUrl(
            """
                {
                  "id": 123,
                  "assets": [
                    {
                      "name": "ClipMaster-v0.4.1.apk",
                      "browser_download_url": "https://gitee.com/download/apk"
                    },
                    {
                      "name": "update.json",
                      "browser_download_url": "https://gitee.com/download/update.json"
                    }
                  ]
                }
            """.trimIndent()
        )

        assertEquals("https://gitee.com/download/update.json", url)
    }

    @Test
    /** 远端版本号不高于当前版本时应返回 UpToDate。 */
    fun parseReturnsUpToDateWhenVersionCodeIsNotNewer() {
        /** 版本号与当前安装包相同的解析结果。 */
        val result = parser.parse(updateJson(versionCode = 21), currentApp)

        assertTrue(result is AppUpdateCheckResult.UpToDate)
        assertEquals(21, (result as AppUpdateCheckResult.UpToDate).versionCode)
    }

    @Test
    /** 非法 JSON 应统一映射成 InvalidJson。 */
    fun parseRejectsInvalidJsonAsInvalidJson() {
        /** 非法 JSON 输入的解析结果。 */
        val result = parser.parse("{", currentApp)

        assertFailure(result, AppUpdateFailureReason.InvalidJson)
    }

    @Test
    /** 缺少必填字段的 JSON 同样应按 InvalidJson 处理。 */
    fun parseRejectsMissingRequiredFieldAsInvalidJson() {
        /** 缺少大部分必填字段的最小错误 JSON 解析结果。 */
        val result = parser.parse("""{"schemaVersion":1}""", currentApp)

        assertFailure(result, AppUpdateFailureReason.InvalidJson)
    }

    @Test
    /** 不支持的 schemaVersion 必须被拒绝，避免旧客户端误读新协议。 */
    fun parseRejectsUnsupportedSchema() {
        /** schemaVersion 升级后的解析结果。 */
        val result = parser.parse(updateJson(schemaVersion = 2), currentApp)

        assertFailure(result, AppUpdateFailureReason.SchemaUnsupported)
    }

    @Test
    /** manifest 包名和当前应用不一致时必须拒绝。 */
    fun parseRejectsPackageMismatch() {
        /** 指向其他包名的 manifest 解析结果。 */
        val result = parser.parse(updateJson(packageName = "other.package"), currentApp)

        assertFailure(result, AppUpdateFailureReason.PackageMismatch)
    }

    @Test
    /** manifest 渠道与当前安装包渠道不一致时必须拒绝。 */
    fun parseRejectsChannelMismatch() {
        /** 指向其他渠道的 manifest 解析结果。 */
        val result = parser.parse(updateJson(channel = "stable"), currentApp)

        assertFailure(result, AppUpdateFailureReason.ChannelMismatch)
    }

    @Test
    /** 没有下载源也没有 fallback 发布页时，应返回 NoDownloadSource。 */
    fun parseRejectsUpdateWithoutAnyVisibleDownloadSource() {
        /** 没有任何可展示入口时的解析结果。 */
        val result = parser.parse(
            updateJson(downloads = "[]", fallbackReleasePage = "null"),
            currentApp,
        )

        assertFailure(result, AppUpdateFailureReason.NoDownloadSource)
    }

    /** 断言解析结果是指定失败原因，减少重复样板代码。 */
    private fun assertFailure(result: AppUpdateCheckResult, reason: AppUpdateFailureReason) {
        assertTrue(result is AppUpdateCheckResult.Failed)
        assertEquals(reason, (result as AppUpdateCheckResult.Failed).reason)
    }

    /** 构造可按需覆盖字段的最小 manifest 文本。 */
    private fun updateJson(
        schemaVersion: Int = 1,
        channel: String = "internal",
        packageName: String = "com.cla.clip.master",
        versionCode: Int = 22,
        downloads: String = DEFAULT_DOWNLOADS,
        fallbackReleasePage: String = DEFAULT_FALLBACK,
    ): String {
        return """
            {
              "schemaVersion": $schemaVersion,
              "channel": "$channel",
              "packageName": "$packageName",
              "versionCode": $versionCode,
              "versionName": "0.4.1",
              "minSupportedVersionCode": 22,
              "forceUpdate": false,
              "publishedAt": "2026-05-24T20:00:00+08:00",
              "sha256": "APK_SHA256",
              "changelog": ["修复若干问题", "优化稳定性"],
              "fallbackReleasePage": $fallbackReleasePage,
              "downloads": $downloads
            }
        """.trimIndent()
    }

    private companion object {
        /** 默认 fallback 发布页 JSON 片段。 */
        const val DEFAULT_FALLBACK = """
            {
              "name": "Gitee Release",
              "url": "https://gitee.com/clip-master-2/clip-master-releases/releases"
            }
        """

        /** 默认下载源列表 JSON 片段；包含 GitHub 和 Gitee 两个入口。 */
        const val DEFAULT_DOWNLOADS = """
            [
              {
                "id": "github",
                "name": "GitHub Release",
                "url": "https://github.com/clip-master-2/ClipMaster-Releases/releases/download/v0.4.1/ClipMaster-v0.4.1.apk",
                "recommendedForChina": false
              },
              {
                "id": "gitee",
                "name": "Gitee Release",
                "url": "https://gitee.com/clip-master-2/clip-master-releases/releases/download/v0.4.1/ClipMaster-v0.4.1.apk",
                "recommendedForChina": true
              }
            ]
        """
    }
}
