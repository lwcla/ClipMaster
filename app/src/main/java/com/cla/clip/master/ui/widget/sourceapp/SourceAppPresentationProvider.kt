package com.cla.clip.master.ui.widget.sourceapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.cla.clip.master.sourceapp.SourceAppPresentationStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.awaitCancellation

/** 共享视觉组件仅接收来源展示接口；生命周期和依赖访问留在页面薄适配中。 */
internal interface SourceAppPresentationProvider {
    /** 不同步访问系统或数据库，取得已聚合的单包名状态。 */
    @Composable fun presentation(packageName: String?): SourceAppPresentationState
    /** 文件实际解码失败的反馈，由适配层去重并调度修复。 */
    fun reportDecodeFailure(packageName: String?)
}

/** Preview/独立组件默认来源状态，不初始化数据库或平台读取。 */
internal object EmptySourceAppPresentationProvider : SourceAppPresentationProvider {
    /** 未提供页面适配时显示默认来源，Preview 不依赖真实应用。 */
    @Composable override fun presentation(packageName: String?) = SourceAppPresentationState.Unavailable
    /** Preview 解码失败没有业务副作用。 */
    override fun reportDecodeFailure(packageName: String?) = Unit
}

/** 应用级依赖入口仅用于页面来源适配，不让卡片直接依赖仓库。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SourceAppPresentationEntryPoint {
    /** 返回唯一聚合 Store，避免每个页面构造独立平台缓存。 */
    fun sourceAppStore(): SourceAppPresentationStore
}

/** 页面记忆的稳定接口，实际活跃行共享查询并按生命周期收集。 */
@Composable
internal fun rememberSourceAppPresentationProvider(): SourceAppPresentationProvider {
    /** 应用 Context 是唯一 DI 定位入口，不持有 Activity。 */
    val context = LocalContext.current.applicationContext
    /** 页面使用的应用级 Store；只创建依赖不会启动数据库观察。 */
    val store = remember(context) { EntryPointAccessors.fromApplication(context, SourceAppPresentationEntryPoint::class.java).sourceAppStore() }
    return remember(store) {
        object : SourceAppPresentationProvider {
            /** 每个可见/预取行提出包名需求，但同包名不会产生独立 DAO 查询。 */
            @Composable override fun presentation(packageName: String?): SourceAppPresentationState {
                if (packageName.isNullOrBlank()) return SourceAppPresentationState.Unavailable
                /** Flow 身份在组合期间保持，页面后台暂停时也不被 Store 淘汰。 */
                val state = remember(store, packageName) { store.state(packageName) }
                DisposableEffect(store, packageName) {
                    store.retain(packageName)
                    onDispose { store.forget(packageName) }
                }
                SourceAppDemandEffect(store, setOf(packageName))
                /** 生命周期收集单包名等值状态，不把元数据合并回 PagingData。 */
                val value by state.collectAsStateWithLifecycle()
                return value
            }

            /** 菜单/卡片共用反馈，不把真实路径和包名写入日志。 */
            override fun reportDecodeFailure(packageName: String?) {
                if (!packageName.isNullOrBlank()) store.reportDecodeFailure(packageName)
            }
        }
    }
}

/** 搜索候选返回后预先提出补齐需求；文件检查不阻塞候选列表显示。 */
@Composable
internal fun SourceAppCandidateRepairEffect(packages: Set<String>) {
    /** 搜索页面的应用 Context 和共享 Store，不在菜单 item 中查 DI。 */
    val context = LocalContext.current.applicationContext
    /** 与结果卡片共用的应用级依赖，创建时不启动任务。 */
    val store = remember(context) { EntryPointAccessors.fromApplication(context, SourceAppPresentationEntryPoint::class.java).sourceAppStore() }
    SourceAppDemandEffect(store, packages)
}

/** 前台激活，离开释放；同包名跨页面引用由 Store 汇总而不是互相取消。 */
@Composable
private fun SourceAppDemandEffect(store: SourceAppPresentationStore, packages: Set<String>) {
    /** 当前页面生命周期，不以菜单开关替代前台状态。 */
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    /** 当前组合会话的稳定身份；同包名多行各自释放自己的引用。 */
    val owner = remember { Any() }
    LaunchedEffect(store, lifecycle, packages) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            store.acquire(owner, packages)
            try { awaitCancellation() } finally { store.release(owner) }
        }
    }
}
