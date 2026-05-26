package com.cla.clip.master.update

import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import java.io.IOException
import javax.inject.Inject

/**
 * 更新检查链路日志接口。
 *
 * 单独抽成接口，方便单元测试直接替换成空实现，也避免检查器直接依赖 Android 日志工具。
 */
interface AppUpdateLogger {
    /** 记录一次检查开始时的触发场景、当前版本和限频上下文。 */
    fun logCheckStart(
        /** 本次检查是手动触发还是自动触发。 */
        trigger: AppUpdateCheckTrigger,

        /** 当前安装包的版本号，用于后续和目标版本做对照。 */
        currentVersionCode: Int,

        /** 是否绕过自动限频直接执行。 */
        forceCheck: Boolean,

        /** 调用方判断出的限频命中状态，便于诊断为什么本次没有继续向下执行。 */
        throttleHit: Boolean,
    )

    /** 记录单次远端请求成功及其耗时。 */
    fun logFetchSuccess(statusCode: Int, elapsedMillis: Long)

    /** 记录某个数据源失败后切换到下一个后备源。 */
    fun logSourceFallback(sourceId: String, reason: AppUpdateFailureReason, statusCode: Int?)

    /** 记录 HTTP 层失败。 */
    fun logHttpError(statusCode: Int, elapsedMillis: Long)

    /** 记录网络层 IO 失败。 */
    fun logNetworkError(throwable: IOException, elapsedMillis: Long)

    /** 记录非 IO 类型的未知异常。 */
    fun logUnknownError(throwable: Throwable, elapsedMillis: Long)

    /** 记录 manifest 解析后的最终结果。 */
    fun logParseResult(result: AppUpdateCheckResult, channel: String)
}

/** Android 运行时使用的更新日志实现。 */
class AndroidAppUpdateLogger @Inject constructor() : AppUpdateLogger {
    override fun logCheckStart(
        trigger: AppUpdateCheckTrigger,
        currentVersionCode: Int,
        forceCheck: Boolean,
        throttleHit: Boolean,
    ) {
        logI(TAG) {
            "检查更新开始 trigger=${trigger.code} currentVersionCode=$currentVersionCode forceCheck=$forceCheck throttleHit=$throttleHit"
        }
    }

    override fun logFetchSuccess(statusCode: Int, elapsedMillis: Long) {
        logI(TAG) {
            "更新 manifest 请求成功 statusCode=$statusCode elapsedMs=$elapsedMillis retryable=false reasonCode=ok"
        }
    }

    override fun logSourceFallback(sourceId: String, reason: AppUpdateFailureReason, statusCode: Int?) {
        logW(TAG) {
            "更新 manifest 源不可用 sourceId=$sourceId reasonCode=${reason.code} statusCode=${statusCode ?: 0}"
        }
    }

    override fun logHttpError(statusCode: Int, elapsedMillis: Long) {
        logW(TAG) {
            "更新 manifest 请求失败 statusCode=$statusCode elapsedMs=$elapsedMillis retryable=true reasonCode=${AppUpdateFailureReason.HttpError.code}"
        }
    }

    override fun logNetworkError(throwable: IOException, elapsedMillis: Long) {
        logW(TAG, throwable) {
            "更新 manifest 网络不可达 elapsedMs=$elapsedMillis retryable=true reasonCode=${AppUpdateFailureReason.NetworkError.code}"
        }
    }

    override fun logUnknownError(throwable: Throwable, elapsedMillis: Long) {
        logW(TAG, throwable) {
            "更新 manifest 未知失败 elapsedMs=$elapsedMillis retryable=true reasonCode=${AppUpdateFailureReason.UnknownError.code}"
        }
    }

    /**
     * 记录解析后的最终结果。
     *
     * 成功和失败都统一在这里落日志，方便从单一 TAG 下串起一次检查的完整链路。
     */
    override fun logParseResult(result: AppUpdateCheckResult, channel: String) {
        when (result) {
            is AppUpdateCheckResult.UpdateAvailable -> {
                logI(TAG) {
                    "更新 manifest 解析成功 targetVersionCode=${result.info.versionCode} channel=$channel hasUpdate=true forceUpdate=${result.info.forceUpdate}"
                }
            }

            is AppUpdateCheckResult.UpToDate -> {
                logI(TAG) {
                    "更新 manifest 解析成功 targetVersionCode=${result.versionCode} channel=$channel hasUpdate=false forceUpdate=false reasonCode=${AppUpdateFailureReason.VersionNotNewer.code}"
                }
            }

            is AppUpdateCheckResult.Failed -> {
                logW(TAG) {
                    "更新 manifest 解析失败 reasonCode=${result.reason.code} statusCode=${result.statusCode ?: 0}"
                }
            }
        }
    }

    private companion object {
        /** 更新链路统一日志 TAG。 */
        const val TAG = "AppUpdateChecker"
    }
}
