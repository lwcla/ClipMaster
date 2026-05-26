package com.cla.clip.master.ui.page.mine

import com.cla.clip.master.update.AppUpdateFailureReason
import com.cla.clip.master.update.AppUpdateInfo
import com.cla.clip.master.update.AppUpdateLink

/** “我的”页更新入口需要展示的状态。 */
data class AppUpdateUiState(
    /** 当前安装版本名，用于默认说明文案。 */
    val currentVersionName: String,

    /** 当前安装版本号，用于默认说明文案。 */
    val currentVersionCode: Int,

    /** 当前是否正在执行检查更新；true 时入口展示“检查中”。 */
    val checking: Boolean = false,

    /** 当前要展示的结果对话框；为空表示不弹任何更新结果。 */
    val dialog: AppUpdateDialogState? = null,
)

/** 更新检查对话框状态。 */
sealed interface AppUpdateDialogState {
    /** 发现新版本时展示版本信息、更新说明和下载入口。 */
    data class UpdateAvailable(val info: AppUpdateInfo) : AppUpdateDialogState

    /** 手动检查且当前已是最新版本时展示的提示。 */
    data class UpToDate(
        /** 远端确认的当前最新版本名。 */
        val versionName: String,

        /** 远端确认的当前最新版本号。 */
        val versionCode: Int,
    ) : AppUpdateDialogState

    /** 检查失败但仍可能有发布页可供用户手动查看时展示的状态。 */
    data class CheckUnavailable(
        /** 当前检查失败的统一原因。 */
        val reason: AppUpdateFailureReason,

        /** 即使检查失败也可展示给用户的手动发布页入口。 */
        val fallbackReleasePage: AppUpdateLink?,
    ) : AppUpdateDialogState
}
