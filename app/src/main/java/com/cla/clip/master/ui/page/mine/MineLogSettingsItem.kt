package com.cla.clip.master.ui.page.mine

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cla.clip.base.general.utils.toast
import com.cla.clip.master.R
import com.cla.clip.master.logging.*
import com.cla.clip.master.ui.widget.LogSettingsEntryCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 我的页薄接线负责权限与外部 Activity；共享日志卡片不持有 VM 或 Context。 */
@Composable
internal fun MineLogSettingsItem(visibleToUser: Boolean, viewModel: LogSettingsVm = hiltViewModel()) {
    /** 当前 Activity Context 只用于权限、外部打开及资源化提示。 */
    val context = LocalContext.current
    /** 外部点击异步检查存在性，不在主线程扫描公共目录。 */
    val scope = rememberCoroutineScope()
    /** 页面生命周期返回时刷新实际数量。 */
    val owner = LocalLifecycleOwner.current
    /** Pager 不可见时不订阅目录冷流，页面 STARTED 也不能替代实际 Tab 可见性。 */
    val source = remember(visibleToUser, viewModel) { if (visibleToUser) viewModel.state else flowOf(LogSettingsEntryState()) }
    /** lifecycle 与 Pager 可见性共同约束数据工作。 */
    val state by source.collectAsStateWithLifecycle(initialValue = LogSettingsEntryState())
    /** 旧系统只在前台开启动作申请写权限，拒绝不改变已提交开关。 */
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.setEnabled(true) else scope.launch { context.toast(R.string.host_log_permission_missing) }
    }
    /** 系统目录 VIEW 不可用时只选择查看文件，不重新设置保存位置。 */
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        /** 用户明确选择授权的 URI；取消返回 null，没有日志控制副作用。 */
        val uri = result.data?.data
        if (uri != null && !LogDirectoryOpener.tryOpenFile(context, uri)) scope.launch { context.toast(R.string.host_log_open_failed) }
        viewModel.refreshFiles()
    }
    DisposableEffect(owner, visibleToUser, viewModel) {
        /** 仅可见页返回前台才重新枚举，观察器离开组合时释放。 */
        val observer = LifecycleEventObserver { _, event ->
            if (visibleToUser && event == Lifecycle.Event.ON_RESUME) viewModel.refreshFiles()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(visibleToUser, viewModel) {
        if (visibleToUser) viewModel.errors.collect { context.toast(R.string.host_settings_save_failed_retry) }
    }
    LogSettingsEntryCard(
        state = state,
        onCheckedChange = { enabled ->
            if (enabled && Build.VERSION.SDK_INT < 29) permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            else viewModel.setEnabled(enabled)
        },
        onOpenFolder = {
            scope.launch {
                /** 只检查固定目录是否生成，不为浏览偷偷打开日志开关。 */
                @Suppress("DEPRECATION")
                val exists = withContext(Dispatchers.IO) {
                    java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "ClipMaster/logs").isDirectory
                }
                if (!exists) context.toast(R.string.host_log_folder_missing)
                else if (!LogDirectoryOpener.tryOpen(context)) {
                    runCatching { picker.launch(LogDirectoryOpener.pickerIntent()) }
                        .onFailure { context.toast(R.string.host_log_open_failed) }
                }
            }
        },
        onRetry = viewModel::refreshFiles,
    )
}
