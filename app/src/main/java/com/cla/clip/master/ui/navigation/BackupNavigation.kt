package com.cla.clip.master.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import androidx.navigation.toRoute
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logW
import com.cla.clip.feature.torrent.search.api.TorrentSearchFeatureEntry
import com.cla.clip.master.ui.page.backup.BackupFlowVm
import com.cla.clip.master.ui.page.backup.BackupMediaRelocationPage
import com.cla.clip.master.ui.page.backup.BackupPage
import com.cla.clip.master.ui.page.backup.BackupRestoreFlowPage

/** 注册备份子流程；同一宿主 NavController 继承全局转场，业务 VM 仍属于各自目的地。 */
internal fun NavGraphBuilder.backupNavigation(
    navController: NavHostController,
    torrentSearchFeatures: Set<TorrentSearchFeatureEntry>,
) {
    navigation<BackupGraphRoute>(startDestination = BackupRoute) {
        composable<BackupRoute> { entry ->
            /** 唯一共享 VM 只按父图 owner 获取，不能用页面默认 owner。 */
            val flowVm = rememberBackupFlowVm(navController, entry)
            BackupPage(
                flowSession = flowVm.session,
                onBack = { navController.closeBackupGraph(flowVm, entry, "backup_back") },
                onNavigate = { route -> navController.navigateWithinBackup(flowVm, entry, route) },
            )
        }
        composable<BackupRestoreRoute> { entry ->
            /** 普通返回退出恢复目的地；结果完成才关闭父图。 */
            val flowVm = rememberBackupFlowVm(navController, entry)
            BackupRestoreFlowPage(
                flowSession = flowVm.session,
                onBack = { if (navController.currentBackStackEntry === entry) navController.popBackStack() },
                onInvalidSession = { navController.returnToBackupStart(entry) },
                onCloseRestoreFlow = { navController.closeBackupGraph(flowVm, entry, "restore_flow_done") },
                onNavigate = { route -> navController.navigateWithinBackup(flowVm, entry, route) },
                torrentSearchFeatures = torrentSearchFeatures,
            )
        }
        composable<BackupMediaRelocationRoute> { entry ->
            /** 轻量路由身份还需同一会话中的真实结果授权，不能直接恢复扫描。 */
            val route = entry.toRoute<BackupMediaRelocationRoute>()
            /** 与恢复页共用父图 VM，扫描 VM 仍为当前媒体目的地所有。 */
            val flowVm = rememberBackupFlowVm(navController, entry)
            BackupMediaRelocationPage(
                restoreTaskId = route.restoreTaskId,
                flowSession = flowVm.session,
                onBack = { if (navController.currentBackStackEntry === entry) navController.popBackStack() },
                onInvalidSession = { navController.returnToBackupStart(entry) },
                onCloseRestoreFlow = { navController.closeBackupGraph(flowVm, entry, "media_relocation_done") },
            )
        }
    }
}

/** owner 跟随 entry 缓存，退出转场期间不重新查找已弹出的父图。 */
@Composable
private fun rememberBackupFlowVm(navController: NavHostController, entry: NavBackStackEntry): BackupFlowVm {
    /** 三个页面缓存同一父 entry，新图不能复用旧 owner。 */
    val parentEntry = remember(entry) { navController.getBackStackEntry<BackupGraphRoute>() }
    return hiltViewModel(parentEntry)
}

/** 组内导航复核请求/报告与来源，拒绝无效子路由和旧页面重复点击。 */
private fun NavHostController.navigateWithinBackup(flowVm: BackupFlowVm, sourceEntry: NavBackStackEntry, route: Route) {
    // 即使新组目的地类型相同，退出转场中的旧回调也不能操作它。
    if (currentBackStackEntry !== sourceEntry) return
    /** 当前未接管请求用于明确导航失败时仅撤销同一个打开动作。 */
    val pending = flowVm.session.requests.value
    /** 许可同时约束业务事实和当前目的地。 */
    val allowed = when (route) {
        BackupRestoreRoute -> pending != null && currentDestination?.hasRoute<BackupRoute>() == true
        is BackupMediaRelocationRoute -> flowVm.session.canOpenMedia(route.restoreTaskId) && currentDestination?.hasRoute<BackupRestoreRoute>() == true
        else -> false
    }
    if (!allowed) {
        logW("BackupNavigation") { "组内导航已拒绝 reasonCode=invalid_backup_destination" }
        return
    }
    try {
        navigate(route) { launchSingleTop = true }
    } catch (error: IllegalArgumentException) {
        // 只撤销未接管请求，日志不打印 route 或异常原文中的敏感参数。
        if (route == BackupRestoreRoute && pending != null) flowVm.session.clearPendingRequest(pending.requestId)
        logW("BackupNavigation") { "组内导航失败 reasonCode=navigation_failed type=${error.javaClass.simpleName}" }
    }
}

/** 整组完成先撤销写权限，再 inclusive 弹父图，不保存已完成组。 */
private fun NavHostController.closeBackupGraph(flowVm: BackupFlowVm, sourceEntry: NavBackStackEntry, reasonCode: String) {
    // 先确认来源，再领取一次关闭许可，旧完成按钮重入不能弹出组外页面。
    if (currentBackStackEntry !== sourceEntry || !flowVm.close(reasonCode)) return
    /** 缺父图仅在仍属于回调来源时退当前页，不重建首页或改变首页 Tab。 */
    val closedGraph = popBackStack<BackupGraphRoute>(inclusive = true)
    if (!closedGraph && currentBackStackEntry === sourceEntry) popBackStack()
    logD("BackupNavigation") { "备份导航组已关闭 closedGraph=$closedGraph reasonCode=$reasonCode" }
}

/** 进程死亡缺失请求/结果时降级到唯一备份起点，禁止自动重做写库。 */
private fun NavHostController.returnToBackupStart(sourceEntry: NavBackStackEntry) {
    // 已弹出的子页可能仍在退出转场中，不能再把刚进入的新流程收掉。
    if (currentBackStackEntry !== sourceEntry) return
    logW("BackupNavigation") { "恢复页面回到备份起点 reasonCode=session_missing" }
    if (!popBackStack<BackupRoute>(inclusive = false)) {
        navigate(BackupRoute) {
            popUpTo<BackupGraphRoute> { inclusive = false }
            launchSingleTop = true
        }
    }
}
