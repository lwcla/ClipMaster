package com.cla.clip.master.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** AppUpdateChecker 回退和失败映射规则测试。 */
class AppUpdateCheckerTest {
    /** 多数据源检查场景共用的基础配置。 */
    private val config = AppUpdateCheckConfig(
        manifestSources = listOf(
            AppUpdateManifestSource(
                id = "gitee",
                name = "Gitee Release",
                url = "https://gitee.example.test/api/v5/repos/clip-master-2/clip-master-releases/releases/latest",
                format = AppUpdateManifestFormat.GiteeRelease,
                releasePage = releasePage("giteeReleasePage", "Gitee Release"),
            ),
            AppUpdateManifestSource(
                id = "github",
                name = "GitHub Release",
                url = "https://github.example.test/update.json",
                format = AppUpdateManifestFormat.DirectJson,
                releasePage = releasePage("githubReleasePage", "GitHub Release"),
            ),
        ),
        currentApp = AppUpdateCurrentApp(
            packageName = "com.cla.clip.master",
            versionCode = 21,
            versionName = "0.4.0",
            channel = "internal",
        ),
        fallbackReleasePage = releasePage("giteeReleasePage", "Gitee Release"),
    )

    @Test
    /** Gitee Release API 能解析出 update.json 且返回 200 时，应正确产出可更新结果。 */
    fun checkReturnsUpdateAvailableForHttp200Manifest() = runBlocking {
        val checker = checker(
            mapOf(
                config.manifestSources[0].url to AppUpdateFetchResult.Success(
                    body = giteeReleaseJson("https://gitee.example.test/update.json"),
                    statusCode = 200,
                    elapsedMillis = 10L,
                ),
                "https://gitee.example.test/update.json" to AppUpdateFetchResult.Success(
                    body = updateJson(),
                    statusCode = 200,
                    elapsedMillis = 10L,
                ),
            )
        )

        /** 只保留 Gitee 数据源，验证单源情况下也能走完整二次抓取链路。 */
        val giteeOnlyConfig = config.copy(manifestSources = listOf(config.manifestSources.first()))
        val result = checker.check(giteeOnlyConfig, AppUpdateCheckTrigger.Manual, forceCheck = true, throttleHit = false)

        assertTrue(result is AppUpdateCheckResult.UpdateAvailable)
        assertEquals(22, (result as AppUpdateCheckResult.UpdateAvailable).info.versionCode)
    }

    @Test
    /** HTTP 非 2xx 要映射成 HttpError，并保留当前数据源对应的 fallback 发布页。 */
    fun checkMapsHttpErrorToReasonAndKeepsFallbackPage() = runBlocking {
        val checker = checker(
            AppUpdateFetchResult.HttpError(statusCode = 503, elapsedMillis = 10L),
            manifestSources = listOf(config.manifestSources.first()),
        )

        val giteeOnlyConfig = config.copy(manifestSources = listOf(config.manifestSources.first()))
        val result = checker.check(giteeOnlyConfig, AppUpdateCheckTrigger.Manual, forceCheck = true, throttleHit = false)

        assertTrue(result is AppUpdateCheckResult.Failed)
        /** 失败结果对象；后续断言失败原因、状态码和 fallback 发布页都从这里读取。 */
        val failed = result as AppUpdateCheckResult.Failed
        assertEquals(AppUpdateFailureReason.HttpError, failed.reason)
        assertEquals(503, failed.statusCode)
        assertEquals(giteeOnlyConfig.manifestSources.first().releasePage, failed.fallbackReleasePage)
    }

    @Test
    /** IO 异常要映射成 NetworkError，并继续保留 fallback 发布页。 */
    fun checkMapsIOExceptionToNetworkError() = runBlocking {
        val checker = checker(
            AppUpdateFetchResult.NetworkError(IOException("offline"), elapsedMillis = 10L),
            manifestSources = listOf(config.manifestSources.first()),
        )

        val giteeOnlyConfig = config.copy(manifestSources = listOf(config.manifestSources.first()))
        val result = checker.check(giteeOnlyConfig, AppUpdateCheckTrigger.Auto, forceCheck = false, throttleHit = false)

        assertTrue(result is AppUpdateCheckResult.Failed)
        assertEquals(AppUpdateFailureReason.NetworkError, (result as AppUpdateCheckResult.Failed).reason)
        assertEquals(giteeOnlyConfig.manifestSources.first().releasePage, result.fallbackReleasePage)
    }

    @Test
    /** 解析失败要落到 InvalidJson，而不是错误地当成网络失败。 */
    fun checkMapsParserFailureAndKeepsFallbackPage() = runBlocking {
        /** 只保留 GitHub 直链源，验证 direct json 分支的失败映射。 */
        val githubOnlyConfig = config.copy(manifestSources = listOf(config.manifestSources.last()))
        val checker = checker(AppUpdateFetchResult.Success(body = "{", statusCode = 200, elapsedMillis = 10L))

        val result = checker.check(githubOnlyConfig, AppUpdateCheckTrigger.Manual, forceCheck = true, throttleHit = false)

        assertTrue(result is AppUpdateCheckResult.Failed)
        assertEquals(AppUpdateFailureReason.InvalidJson, (result as AppUpdateCheckResult.Failed).reason)
        assertEquals(githubOnlyConfig.manifestSources.first().releasePage, result.fallbackReleasePage)
    }

    @Test
    /** Gitee release 没挂 update.json 时，应继续回退到下一个后备源。 */
    fun checkFallsBackToGithubWhenGiteeReleaseCannotProvideUpdateJson() = runBlocking {
        val checker = checker(
            mapOf(
                config.manifestSources[0].url to AppUpdateFetchResult.Success(
                    body = giteeReleaseJson(updateJsonUrl = null),
                    statusCode = 200,
                    elapsedMillis = 10L,
                ),
                config.manifestSources[1].url to AppUpdateFetchResult.Success(
                    body = updateJson(),
                    statusCode = 200,
                    elapsedMillis = 10L,
                ),
            )
        )

        val result = checker.check(config, AppUpdateCheckTrigger.Manual, forceCheck = true, throttleHit = false)

        assertTrue(result is AppUpdateCheckResult.UpdateAvailable)
        assertEquals(22, (result as AppUpdateCheckResult.UpdateAvailable).info.versionCode)
    }

    /** 为单一返回结果快速构造检查器；适合只验证某一个源统一失败映射的场景。 */
    private fun checker(
        fetchResult: AppUpdateFetchResult,
        manifestSources: List<AppUpdateManifestSource> = config.manifestSources,
    ): AppUpdateChecker {
        /** 把同一个抓取结果映射给当前测试用到的所有 manifest 数据源。 */
        val responses = manifestSources.associate { it.url to fetchResult }
        return checker(responses)
    }

    /** 按 URL 精确返回测试预设抓取结果的检查器。 */
    private fun checker(fetchResults: Map<String, AppUpdateFetchResult>): AppUpdateChecker {
        return AppUpdateChecker(
            fetcher = object : AppUpdateManifestFetcher {
                override suspend fun fetch(url: String): AppUpdateFetchResult {
                    return fetchResults[url] ?: AppUpdateFetchResult.NetworkError(IOException("missing fake"), 0L)
                }
            },
            parser = AppUpdateManifestParser(),
            logger = NoOpAppUpdateLogger,
        )
    }

    /** 构造测试用 fallback 发布页。 */
    private fun releasePage(id: String, name: String): AppUpdateLink {
        return AppUpdateLink(
            id = id,
            name = name,
            url = "https://example.test/$id",
            recommendedForChina = id.contains("gitee", ignoreCase = true),
        )
    }

    /** 生成最小可用的测试 update.json。 */
    private fun updateJson(): String {
        return """
            {
              "schemaVersion": 1,
              "channel": "internal",
              "packageName": "com.cla.clip.master",
              "versionCode": 22,
              "versionName": "0.4.1",
              "downloads": [
                {
                  "id": "github",
                  "name": "GitHub Release",
                  "url": "https://github.com/clip-master-2/ClipMaster-Releases/releases/download/v0.4.1/ClipMaster-v0.4.1.apk",
                  "recommendedForChina": false
                }
              ]
            }
        """.trimIndent()
    }

    /** 生成最小可用的 Gitee Release API 响应；可按需决定是否附带 update.json 资产。 */
    private fun giteeReleaseJson(updateJsonUrl: String?): String {
        /** Release 资产 JSON 片段；为空时表示当前发布没有挂 update.json。 */
        val assets = if (updateJsonUrl == null) {
            """[]"""
        } else {
            """
            [
              {
                "id": 1,
                "name": "update.json",
                "browser_download_url": "$updateJsonUrl"
              }
            ]
            """.trimIndent()
        }
        return """
            {
              "id": 123,
              "tag_name": "v0.4.1",
              "assets": $assets
            }
        """.trimIndent()
    }

    /** 测试用空日志实现；避免断言时受日志副作用干扰。 */
    private object NoOpAppUpdateLogger : AppUpdateLogger {
        override fun logCheckStart(
            trigger: AppUpdateCheckTrigger,
            currentVersionCode: Int,
            forceCheck: Boolean,
            throttleHit: Boolean,
        ) = Unit

        override fun logFetchSuccess(statusCode: Int, elapsedMillis: Long) = Unit

        override fun logSourceFallback(sourceId: String, reason: AppUpdateFailureReason, statusCode: Int?) = Unit

        override fun logHttpError(statusCode: Int, elapsedMillis: Long) = Unit

        override fun logNetworkError(throwable: IOException, elapsedMillis: Long) = Unit

        override fun logUnknownError(throwable: Throwable, elapsedMillis: Long) = Unit

        override fun logParseResult(result: AppUpdateCheckResult, channel: String) = Unit
    }
}
