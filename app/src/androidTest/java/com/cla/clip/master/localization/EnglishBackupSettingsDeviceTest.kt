package com.cla.clip.master.localization

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.cla.clip.base.general.backup.*
import com.cla.clip.base.general.config.ClipItemQuickAction
import com.cla.clip.master.R
import com.cla.clip.master.backup.*
import com.cla.clip.master.entity.SettingSwitchItemUi
import com.cla.clip.master.logging.*
import com.cla.clip.master.preview.LinkPreviewToggleState
import com.cla.clip.master.ui.page.backup.*
import com.cla.clip.master.ui.page.mine.*
import com.cla.clip.master.ui.widget.*
import com.cla.clip.master.update.*
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import com.cla.clip.base.general.R as BaseR

/** 参数化生产组件回归：每个场景独立挂载，所有外部动作为空回调，不创建真实 VM 或访问用户数据。 */
@RunWith(Parameterized::class)
class EnglishBackupSettingsDeviceTest(
    /** 当前用例只选择合成展示状态，名称同时用于截图归档。 */
    private val scenario: String,
) : EnglishScreenTestBase() {

    /** 下载入口只记录合成链接，验证点击契约时不打开浏览器或执行网络请求。 */
    private val openedUpdateLinks = mutableListOf<AppUpdateLink>()

    /** 披露动作仅留在测试内存，验证双按钮可点击且不会写入确认偏好或创建备份。 */
    private val disclosureActions = mutableListOf<String>()

    /** 覆盖本域真实页面区块和弹窗的不同结构；字号与英语 Context 统一交给共享宿主。 */
    @Test
    fun backupAndSettingsRemainReadable() {
        // 只有用户内容摘要和明确可展开的说明允许省略，操作、错误和固定标题始终完整检查。
        verifyEnglish(
            stage = "backup-settings-$scenario",
            labels = when {
                scenario == "update-available" -> listOf(ReleasePageName, englishText(BaseR.string.base_general_sure))
                scenario.startsWith("consent-") -> listOf(englishText(R.string.host_first_launch_accept), englishText(R.string.host_first_launch_defer))
                scenario.startsWith("restore-exit-") -> listOf(englishText(BaseR.string.base_general_backup_restore_exit_confirm), englishText(BaseR.string.base_general_cancel))
                scenario == "backup-disclosure" -> listOf(englishText(BaseR.string.base_general_backup_plaintext_disclosure_confirm), englishText(BaseR.string.base_general_cancel))
                else -> emptyList()
            },
            allowEllipsis = { text ->
                (scenario == "backup-file" && text == LongBackupFileName) ||
                    (scenario.startsWith("permission-") && text == permissionDescription()) ||
                    (scenario.startsWith("preview-") && text == englishText(R.string.host_link_preview_description))
            },
            exercise = { rule ->
                when (scenario) {
                    // 更新末尾按钮只能通过真实滚动到达；旧无滚动正文不能通过此回归。
                    "update-available" -> rule.onNodeWithText(ReleasePageName).performScrollTo()
                    // 设置说明点击只改变展开状态；空业务回调确保不申请权限或写偏好。
                    "permission-shizuku" -> rule.onNodeWithText("Shizuku permission").performClick()
                    "permission-denied", "permission-disabled" -> rule.onNodeWithText("Notification permission").performClick()
                    "preview-failed", "preview-save-failed" -> rule.onNodeWithText("Online previews").performClick()
                    // 单段说明可能高于整个视口，先按可见高度逐屏检查，再按节点定位检查固定动作。
                    "consent-default", "consent-saving", "consent-failed", "backup-disclosure" ->
                        scrollAndInspect("backup-settings-$scenario-body")
                    else -> Unit
                }
                inspectScrollableContent()
                if (scenario == "update-available") {
                    // 每个字号独立记录两个真实按钮的点击；截图人工检查父级圆角未裁掉字形。
                    openedUpdateLinks.clear()
                    rule.onNodeWithText("International download mirror with installation instructions", substring = true)
                        .performScrollTo().assertIsDisplayed()
                    checkAndCapture("backup-settings-$scenario-download-button")
                    rule.onNodeWithText("International download mirror with installation instructions", substring = true)
                        .performClick()
                    rule.onNodeWithText(ReleasePageName).performScrollTo().assertIsDisplayed()
                    checkAndCapture("backup-settings-$scenario-release-button")
                    rule.onNodeWithText(ReleasePageName).performClick()
                    assertEquals(listOf("https://example.invalid/download", releasePage().url), openedUpdateLinks.map { it.url })
                }
                if (scenario == "backup-disclosure") {
                    // 选取合并后的可点击按钮而非内部 Text，确保整个操作区没有彼此覆盖。
                    val confirm = rule.onNode(hasText(englishText(BaseR.string.base_general_backup_plaintext_disclosure_confirm)) and hasClickAction())
                    // 取消按钮与确认按钮必须同时可见，回调仅记录动作顺序。
                    val cancel = rule.onNode(hasText(englishText(BaseR.string.base_general_cancel)) and hasClickAction())
                    confirm.assertIsDisplayed()
                    cancel.assertIsDisplayed()
                    assertFalse("Backup disclosure buttons overlap", confirm.fetchSemanticsNode().boundsInRoot.overlaps(cancel.fetchSemanticsNode().boundsInRoot))
                    disclosureActions.clear()
                    confirm.performClick()
                    cancel.performClick()
                    assertEquals(listOf("confirm", "cancel"), disclosureActions)
                }
            },
        ) {
            when {
                scenario.startsWith("restore-") -> RestoreScenario()
                scenario.startsWith("update-") -> UpdateScenario()
                scenario.startsWith("permission-") -> PermissionScenario()
                scenario.startsWith("preview-") -> PreviewSettingScenario()
                scenario.startsWith("log-") -> LogSettingScenario()
                scenario.startsWith("consent-") -> FirstLaunchConsentDialog(
                    saving = scenario == "consent-saving",
                    saveFailed = scenario == "consent-failed",
                    onAccept = {}, onDefer = {},
                )
                else -> BackupAndEntryScenario()
            }
        }
    }

    /** 逐一把真实滚动正文的文本移入视口并验收，避免只检查弹窗首屏和最后一屏而漏掉中段。 */
    private fun inspectScrollableContent() {
        // scrollableText 仅选择具有滚动祖先的真实 Text；固定标题、关闭按钮不强行执行滚动。
        val scrollableText = hasAnyAncestor(hasScrollAction()) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
        // texts 是当前合成画面的去重全文，来自资源或 fixture，不包含真实用户输入。
        val texts = compose.onAllNodes(scrollableText, useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } }
            .filter { it.isNotBlank() }.distinct()
        texts.forEachIndexed { index, text ->
            // index 只命名截图；text 精确定位当前正文，不执行业务点击。
            compose.onAllNodes(hasText(text) and scrollableText, useUnmergedTree = true)[0].performScrollTo()
            checkAndCapture("backup-settings-$scenario-scroll-$index")
        }
    }

    /** 直接挂载生产恢复骨架，保留固定底栏和正文剩余视口；所有业务事件均为空回调。 */
    @Composable
    private fun RestoreScenario() {
        if (scenario.startsWith("restore-exit-")) {
            BackupRestoreExitDialog(restoring = scenario == "restore-exit-restoring", onDismiss = {}, onConfirm = {})
            return
        }
        // state 仅含合成摘要和计数，没有真实 ZIP、URI、数据库或会话。
        val state: BackupRestoreFlowState = when (scenario) {
            "restore-reading-local" -> BackupRestoreFlowState.Reading("layout", BackupRestoreOpenSource.LocalFile,
                BackupRestoreReadPhase.CopyingAndVerifying, null)
            "restore-reading-remote" -> BackupRestoreFlowState.Reading("layout", BackupRestoreOpenSource.WebDav,
                BackupRestoreReadPhase.DownloadingAndVerifying, null)
            "restore-preview" -> BackupRestoreFlowState.Preview("layout", BackupRestoreOpenSource.LocalDirectory,
                preview(), LongBackupFileName, 99_999_999)
            "restore-running" -> BackupRestoreFlowState.Restoring("layout", BackupRestoreOpenSource.WebDav,
                BackupProgress("layout", BackupProgressPhase.Restoring))
            "restore-verifying" -> BackupRestoreFlowState.Restoring("layout", BackupRestoreOpenSource.LocalFile,
                BackupProgress("layout", BackupProgressPhase.Verifying))
            "restore-error-reading", "restore-error-writing" -> BackupRestoreFlowState.Error(
                "layout", BackupRestoreOpenSource.WebDav,
                if (scenario == "restore-error-reading") BackupRestoreFailureStage.Reading else BackupRestoreFailureStage.Restoring,
                "synthetic", BaseR.string.base_general_backup_error_insufficient_space,
            )
            else -> BackupRestoreFlowState.Result("layout", BackupRestoreOpenSource.WebDav,
                LongBackupFileName, preview(), report(), SyntheticTime,
                settingsRestoreFailed = scenario == "restore-partial-settings",
                featureRestoreFailed = scenario == "restore-partial-feature")
        }
        BackupRestoreFlowScaffold(
            state = state,
            onBack = {}, onDone = {}, onForceBack = {}, onRestore = {},
            torrentSearchFeatures = emptySet(),
        )
    }

    /** 版本信息只用于展示；地址使用保留域名且点击为空回调，绝不发起网络或浏览器动作。 */
    @Composable
    private fun UpdateScenario() {
        // state 覆盖三种真实弹窗类型及失败时无发布页的结构。
        val state = when (scenario) {
            "update-available" -> AppUpdateDialogState.UpdateAvailable(AppUpdateInfo(
                versionCode = 999999, versionName = "999.999.999 international preview",
                minSupportedVersionCode = 1, forceUpdate = true,
                publishedAt = "2099-12-31 23:59:59 UTC", sha256 = "0123456789abcdef".repeat(4),
                changelog = List(6) { index ->
                    // index 只区别合成说明段落，长文本用于验证弹窗滚动，不代表真实发布内容。
                    "Change ${index + 1}: Improved clipboard backup and restore accessibility with detailed explanations for large text and narrow screens."
                },
                downloads = listOf(AppUpdateLink("fixture", "International download mirror with installation instructions",
                    "https://example.invalid/download", "TEST", true)),
                fallbackReleasePage = releasePage(),
            ))
            "update-current" -> AppUpdateDialogState.UpToDate("999.999.999 international preview", 999999)
            else -> AppUpdateDialogState.CheckUnavailable(AppUpdateFailureReason.SchemaUnsupported,
                if (scenario == "update-failed") releasePage() else null)
        }
        // 捕获回调仅验证链接身份和顺序，不改变弹窗状态或触发外部副作用。
        AppUpdateDialog(state, { link -> openedUpdateLinks += link }, {})
    }

    /** 权限状态直接传入正式条目，点击只展开说明，不访问系统服务。 */
    @Composable
    private fun PermissionScenario() {
        // shizuku 决定固定权限类型；其余场景覆盖通知拒绝及系统关闭的不同长文案。
        val shizuku = scenario == "permission-shizuku"
        Column(Modifier.verticalScroll(rememberScrollState())) {
            PermissionSettingItems(listOf(SettingSwitchItemUi(
                id = if (shizuku) SettingSwitchItemUi.Id.Permission.Shizuku else SettingSwitchItemUi.Id.Permission.Notice,
                title = stringResource(if (shizuku) R.string.host_shizuku_permission_title else R.string.host_notification_permission_title),
                description = stringResource(permissionDescriptionRes()), checked = false,
            )), { _, _ -> })
        }
    }

    /** 链接预览纯展示适配覆盖未知、读取失败、保存失败和已开启，不创建 Hilt VM。 */
    @Composable
    private fun PreviewSettingScenario() {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            MineLinkPreviewSettingsContent(
                state = when (scenario) {
                    "preview-loading" -> LinkPreviewToggleState.Loading
                    "preview-failed" -> LinkPreviewToggleState.Failed
                    else -> LinkPreviewToggleState.Ready(true)
                },
                saving = false, saveFailed = scenario == "preview-save-failed", onCheckedChange = {}, onRetry = {},
            )
        }
    }

    /** 日志开关状态直接进入共享卡片，文件入口和采集控制均为空回调。 */
    @Composable
    private fun LogSettingScenario() {
        LogSettingsEntryCard(LogSettingsEntryState(
            toggle = when (scenario) {
                "log-loading" -> LogToggleState.Loading
                "log-failed" -> LogToggleState.Failed
                else -> LogToggleState.Ready(true)
            },
            capture = LogCaptureState(if (scenario == "log-capture-failed") "failed" else "recording"),
        ), {}, {}, {})
    }

    /** 总览直接复用原区块，场景不包含会触发文件选择、上传或设置保存的页面入口。 */
    @Composable
    private fun BackupAndEntryScenario() {
        Column(Modifier.verticalScroll(rememberScrollState())) {
        when (scenario) {
            // 合成回调验证按钮契约，不接入真实 ViewModel 的披露持久化和后续备份。
            "backup-disclosure" -> BackupPlaintextDisclosureDialog(
                onDismiss = { disclosureActions += "cancel" },
                onConfirm = { disclosureActions += "confirm" },
            )
            "backup-progress" -> BackupTaskProgressDialog(BackupBusyOperation.Exporting,
                BackupProgress("layout", BackupProgressPhase.WritingLocal))
            "backup-queued" -> AcknowledgementDialog(stringResource(R.string.host_backup_task_created_title),
                stringResource(R.string.host_backup_upload_queued), stringResource(R.string.host_backup_task_created_confirm), {})
            "backup-auto", "backup-auto-failed" -> AutoBackupSection(BackupUiState(settingsLoaded = true,
                autoBackupEnabled = true, backupRetentionCount = 20,
                backupResults = backupResults(scenario == "backup-auto-failed")), {}, {})
            "backup-local-empty", "backup-local-configured" -> {
                LocalBackupSection(BackupUiState(settingsLoaded = true,
                    localBackupDirUri = if (scenario == "backup-local-configured") "synthetic-directory" else "",
                    localBackupDirLabel = "Synthetic backup folder"), false, {}, {}, {}, {})
            }
            "backup-webdav-empty", "backup-webdav-failed" -> {
                WebDavConfigSection(state = BackupUiState(settingsLoaded = true, webDavHealth =
                    if (scenario == "backup-webdav-failed") BackupTargetHealth.Unavailable else BackupTargetHealth.Unknown,
                    webDavHealthCheckedAt = SyntheticTime), onEditingChanged = {}, onEndpointChange = {},
                    onUsernameChange = {}, onPasswordChange = {}, onRemoteDirChange = {}, onAllowHttpChange = {},
                    onTest = {}, onUpload = {}, onRefresh = {}, actionsEnabled = true)
            }
            "backup-file" -> BackupFileCard(LongBackupFileName, SyntheticTime, 99_999_999, false, {})
            "backup-empty" -> EmptyRemoteBackups()
            "mine-backup" -> BackupEntry(backupResults(true), {})
            "mine-filter" -> ClipSourceBlockSettingEntry(99999, {})
            "mine-recycle" -> RecycleBinEntry(99999, {})
            "mine-folded" -> FoldedClipsEntry(99999, {})
            "mine-quick-menu" -> ClipItemActionSettingEntry(ClipItemQuickAction.None, true, {}, {})
            "mine-update" -> AppUpdateEntry(AppUpdateUiState("999.999.999 international preview", 999999), {})
            "mine-update-checking" -> AppUpdateEntry(AppUpdateUiState("999", 999, checking = true), {})
            else -> error("Unknown synthetic UI scenario: $scenario")
        }
        }
    }

    /** 资源选择仅服务测试 fixture，与生产权限判断无关。 */
    private fun permissionDescriptionRes(): Int = when (scenario) {
        "permission-shizuku" -> R.string.host_shizuku_service_require
        "permission-disabled" -> R.string.host_notification_system_disabled_tip
        else -> R.string.host_notification_permission_denied_tip
    }

    /** 仅用于明确白名单的固定英文折叠说明，避免放宽其他标题或失败文本。 */
    private fun permissionDescription(): String = englishText(permissionDescriptionRes())

    /** 从 APK 的英文配置读取 fixture 文案，不改变手机或进程默认语言。 */
    private fun englishText(resource: Int): String {
        // context/configuration 仅生成测试资源上下文，不持有 Activity 或读写用户设置。
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = android.content.res.Configuration(context.resources.configuration).apply {
            setLocales(android.os.LocaleList.forLanguageTags("en-US"))
        }
        return context.createConfigurationContext(configuration).getString(resource)
    }

    /** 合成的备份预检只含展示字段和数量，足够检验长文件名、设备名与版本换行。 */
    private fun preview() = BackupPreview(SyntheticTime, "999.999.999 international preview", 999,
        "Synthetic international device with a deliberately long display label", BackupKind.Manual, true,
        BackupSummary(99999, 88888, 77777, 66666))

    /** 大计数报告保护英文三项结果与分类标题不会互相挤压。 */
    private fun report() = BackupRestoreReport(99999, 88888, 77777,
        listOf("clips", "source_apps", "link_previews", "search_histories").map { category ->
            // category 使用正式稳定 code，只创建内存报告，不调用恢复仓库。
            BackupRestoreCategoryReport(category, 99999, 88888, 77777)
        })

    /** 失败原因使用已有稳定 code，合成摘要不携带真实目标地址或异常文本。 */
    private fun backupResults(failed: Boolean) = BackupEntryState(
        BackupResultPresentation(if (failed) BackupResultKind.Failed else BackupResultKind.LocalUnconfigured,
            reasonCode = "local_directory_conflict"),
        BackupResultPresentation(if (failed) BackupResultKind.Failed else BackupResultKind.WebDavUnconfigured,
            reasonCode = "webdav_request_timeout"),
    )

    /** 保留域名确保即便测试回调误接也不包含真实发布地址。 */
    private fun releasePage() = AppUpdateLink("releasePage", ReleasePageName, "https://example.invalid/release")

    companion object {
        /** 固定 UTC 时间避免截图随真实时间变化，并且不对应用户备份。 */
        private const val SyntheticTime = 4_102_444_799_000L
        /** 文件摘要允许省略；恢复详情仍应显示完整值，设备会检查其实际布局。 */
        private const val LongBackupFileName = "clipmaster-synthetic-international-accessibility-backup-2099-12-31.zip"
        /** 长按钮也是滚动终点，验证真实 Dialog 正文可达底部。 */
        private const val ReleasePageName = "Release details and manual installation instructions"

        /** 各名称对应一个真实可见状态，避免在同一测试反复 setContent 造成宿主冲突。 */
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun scenarios(): List<Array<String>> = listOf(
            "backup-auto", "backup-auto-failed", "backup-local-empty", "backup-local-configured",
            "backup-webdav-empty", "backup-webdav-failed", "backup-file", "backup-empty",
            "backup-disclosure", "backup-progress", "backup-queued",
            "restore-reading-local", "restore-reading-remote", "restore-preview", "restore-running", "restore-verifying",
            "restore-result", "restore-partial-settings", "restore-partial-feature", "restore-error-reading", "restore-error-writing",
            "restore-exit-reading", "restore-exit-restoring",
            "update-available", "update-current", "update-failed", "update-failed-no-link",
            "consent-default", "consent-saving", "consent-failed",
            "mine-backup", "mine-filter", "mine-recycle", "mine-folded", "mine-quick-menu", "mine-update", "mine-update-checking",
            "permission-shizuku", "permission-denied", "permission-disabled",
            "preview-loading", "preview-failed", "preview-save-failed", "preview-enabled",
            "log-loading", "log-failed", "log-recording", "log-capture-failed",
        ).map { scenario -> arrayOf(scenario) }
    }
}
