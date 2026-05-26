package com.cla.clip.master.update

import com.cla.clip.master.BuildConfig
import javax.inject.Inject

/**
 * 构建 App 自升级检查所需的静态配置。
 *
 * 负责把 BuildConfig 中的仓库地址、发布页和当前安装包信息整理成领域对象，
 * 避免调用方直接依赖一组分散的 BuildConfig 字段。
 */
class AppUpdateConfigFactory @Inject constructor() {
    /**
     * 生成当前进程可用的检查更新配置。
     *
     * 会自动过滤空白 URL，保证后续检查器只拿到真实可请求的数据源和发布页入口。
     */
    fun create(): AppUpdateCheckConfig {
        /** GitHub 发布页入口；Gitee 不可用时作为国际网络环境兜底。 */
        val githubReleasePage = BuildConfig.APP_UPDATE_GITHUB_RELEASE_PAGE_URL.toReleasePageOrNull(
            id = "githubReleasePage",
            name = "GitHub Release",
            recommendedForChina = false,
        )
        /** Gitee 发布页入口；国内网络优先展示它，减少用户跨境下载失败概率。 */
        val giteeReleasePage = BuildConfig.APP_UPDATE_GITEE_RELEASE_PAGE_URL.toReleasePageOrNull(
            id = "giteeReleasePage",
            name = "Gitee Release",
            recommendedForChina = true,
        )
        return AppUpdateCheckConfig(
            manifestSources = listOfNotNull(
                BuildConfig.APP_UPDATE_GITEE_RELEASE_API_URL.toManifestSourceOrNull(
                    id = "gitee",
                    name = "Gitee Release",
                    format = AppUpdateManifestFormat.GiteeRelease,
                    releasePage = giteeReleasePage,
                ),
                BuildConfig.APP_UPDATE_GITHUB_MANIFEST_URL.toManifestSourceOrNull(
                    id = "github",
                    name = "GitHub Release",
                    format = AppUpdateManifestFormat.DirectJson,
                    releasePage = githubReleasePage,
                ),
            ),
            currentApp = AppUpdateCurrentApp(
                packageName = BuildConfig.APPLICATION_ID,
                versionCode = BuildConfig.VERSION_CODE,
                versionName = BuildConfig.VERSION_NAME,
                channel = BuildConfig.APP_UPDATE_CHANNEL,
            ),
            fallbackReleasePage = giteeReleasePage ?: githubReleasePage,
        )
    }

    /** 把 BuildConfig 里的 manifest URL 规范化成数据源对象；空白值直接忽略，不让检查器拿到无效源。 */
    private fun String.toManifestSourceOrNull(
        id: String,
        name: String,
        format: AppUpdateManifestFormat,
        releasePage: AppUpdateLink?,
    ): AppUpdateManifestSource? {
        /** 去掉首尾空白后的远端地址；空串说明当前构建没有配置该数据源。 */
        val normalizedUrl = trim().takeIf(String::isNotBlank) ?: return null
        return AppUpdateManifestSource(
            id = id,
            name = name,
            url = normalizedUrl,
            format = format,
            releasePage = releasePage,
        )
    }

    /** 把 BuildConfig 里的发布页地址规范化成下载入口对象；空白值直接忽略。 */
    private fun String.toReleasePageOrNull(
        id: String,
        name: String,
        recommendedForChina: Boolean,
    ): AppUpdateLink? {
        /** 去掉首尾空白后的发布页地址；空串表示当前构建没有提供该手动下载入口。 */
        val normalizedUrl = trim().takeIf(String::isNotBlank) ?: return null
        return AppUpdateLink(
            id = id,
            name = name,
            url = normalizedUrl,
            recommendedForChina = recommendedForChina,
        )
    }
}
