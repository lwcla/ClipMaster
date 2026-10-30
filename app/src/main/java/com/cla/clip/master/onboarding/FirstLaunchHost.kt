package com.cla.clip.master.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cla.clip.base.general.utils.hasNotificationRuntimePermission
import com.cla.clip.base.general.utils.logW
import com.cla.clip.master.R
import com.cla.clip.master.ui.widget.FirstLaunchConsentDialog
import com.cla.clip.master.ui.widget.LocalLinkPreviewNetworkAllowed
import com.cla.clip.shizuku.ShizukuStatus
import com.cla.clip.shizuku.ShizukuUtils

/** 首次启动专属接线，持有权限 launcher 和生命周期；展示组件不依赖 ViewModel 或系统服务。 */
@Composable
internal fun FirstLaunchHost(
    onReadyForForegroundRead: () -> Unit,
    onShizukuGranted: () -> Unit,
    viewModel: FirstLaunchVm = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    /** Activity 级首次说明状态，旋转时复用同一 ViewModel。 */
    val state by viewModel.state.collectAsStateWithLifecycle()
    /** 一次性权限动作不与 UI 开关绑定。 */
    val step by viewModel.permissions.step.collectAsStateWithLifecycle()
    /** 只在宿主恢复后启动系统界面。 */
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    /** 外部跳转及权限检查所需当前 Context。 */
    val context = LocalContext.current
    /** 观察恢复事件，保证通知不会在外部 Shizuku 页面上弹出。 */
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    /** 不论允许或拒绝，通知结果只结束引导。 */
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.permissions.notificationFinished()
    }

    // 宿主只观察外部页面返回；SDK 结果订阅属于 ViewModel，旋转不能移除。
    DisposableEffect(viewModel, lifecycle, context) {
        /** 外部下载/激活引导只在真实暂停后返回时结束，旋转不重放外部跳转。 */
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && viewModel.waitingForExternalReturn) viewModel.externalPagePaused = true
            if (event == Lifecycle.Event.ON_RESUME && viewModel.waitingForExternalReturn && viewModel.externalPagePaused) {
                viewModel.waitingForExternalReturn = false
                viewModel.externalPagePaused = false
                try {
                    when (ShizukuUtils.checkStatus(context)) {
                        is ShizukuStatus.Disconnect.NotGranted -> ShizukuUtils.toConnect()
                        else -> {
                            if (ShizukuUtils.isConnected(context)) onShizukuGranted()
                            viewModel.permissions.shizukuFinished()
                        }
                    }
                } catch (failure: Exception) {
                    logW("FirstLaunch") { "Shizuku 返回引导失败 type=${failure.javaClass.simpleName}" }
                    viewModel.permissions.shizukuFinished()
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }

    // 每个动作先消费再启动，避免重组/配置重建造成多个系统权限窗口叠加。
    LaunchedEffect(step, lifecycleState) {
        if (!lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        if (viewModel.permissions.takeShizukuRequest()) {
            try {
                when (ShizukuUtils.checkStatus(context)) {
                    is ShizukuStatus.Connected -> { onShizukuGranted(); viewModel.permissions.shizukuFinished() }
                    is ShizukuStatus.Disconnect.NotGranted -> ShizukuUtils.toConnect()
                    else -> {
                        viewModel.waitingForExternalReturn = true
                        viewModel.externalPagePaused = false
                        when (ShizukuUtils.checkStatus(context)) {
                            is ShizukuStatus.Disconnect.ServiceNotAlive -> ShizukuUtils.toShizukuApp(context)
                            else -> ShizukuUtils.toDownloadApk(context)
                        }
                    }
                }
            } catch (failure: Exception) {
                viewModel.waitingForExternalReturn = false
                logW("FirstLaunch") { "Shizuku 引导不可用 type=${failure.javaClass.simpleName}" }
                viewModel.permissions.shizukuFinished()
            }
        } else if (viewModel.permissions.takeNotificationRequest()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !context.hasNotificationRuntimePermission()) {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else viewModel.permissions.notificationFinished()
        }
    }

    /** 完成任一选择均释放前台读取，不区分同意与暂不开启。 */
    val ready = (state as? FirstLaunchUiState.Ready)?.settings?.firstLaunchCompleted == true
    LaunchedEffect(ready) { if (ready) onReadyForForegroundRead() }
    when (val current = state) {
        FirstLaunchUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        FirstLaunchUiState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.host_privacy_settings_load_failed))
                TextButton(onClick = viewModel::reload) { Text(stringResource(R.string.host_privacy_settings_retry)) }
            }
        }
        is FirstLaunchUiState.Ready -> {
            if (current.settings.firstLaunchCompleted) {
                CompositionLocalProvider(LocalLinkPreviewNetworkAllowed provides current.settings.previewEnabled) { content() }
            } else {
                FirstLaunchConsentDialog(
                    saving = current.saving, saveFailed = current.saveFailed,
                    onAccept = { viewModel.choose(true) }, onDefer = { viewModel.choose(false) },
                )
            }
        }
    }
}
