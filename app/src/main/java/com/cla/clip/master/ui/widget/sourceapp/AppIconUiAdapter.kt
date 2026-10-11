package com.cla.clip.master.ui.widget.sourceapp

import android.graphics.Bitmap
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
import com.cla.clip.master.sourceapp.AppIconEntry
import com.cla.clip.master.sourceapp.AppIconManager
import com.cla.clip.master.sourceapp.AppIconUiSession
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** 共享 UI 的可替换图标边界；调用入口提供加载器，不将业务单例塞入纯绘制组件。 */
internal fun interface AppIconLoader {
    /** 图标和主色同次返回，失败为空条目，取消直接传播。 */
    suspend fun load(packageName: String): AppIconEntry<Bitmap>
}

/** Preview 默认只绘制占位资源，不执行系统读取。 */
internal object EmptyAppIconLoader : AppIconLoader {
    /** 空加载器不查包名或主题，调用方提供视觉兜底。 */
    override suspend fun load(packageName: String): AppIconEntry<Bitmap> = AppIconEntry(null, null)
}

/** app 层接线专用 Hilt 入口，不扩大 Manager 对外 API。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface AppIconLoaderEntryPoint {
    /** 取得应用级唯一缓存，页面不会持有 Activity Context。 */
    fun icons(): AppIconManager
}

/** 页面一次性取得加载边界，向卡片/菜单传入同一稳定对象。 */
@Composable
internal fun rememberAppIconLoader(): AppIconLoader {
    /** 仅持 Application Context，避免图标缓存随页面泄漏。 */
    val context = LocalContext.current.applicationContext
    return remember(context) {
        /** 业务 DI 只发生于 app adapter，Preview 可直接替换加载器。 */
        val manager = EntryPointAccessors.fromApplication(context, AppIconLoaderEntryPoint::class.java).icons()
        AppIconLoader(manager::load)
    }
}

/** 按包名建立局部会话；重组复用，STARTED/实际可见才加载，离场释放图像。 */
@Composable
internal fun rememberAppIconEntry(
    /** 包名改变会关闭旧会话，防止懒布局复用旧来源图片。 */
    packageName: String?,
    /** 页面提供的稳定加载器，替换加载器也产生新会话。 */
    loader: AppIconLoader,
    /** 首页非当前 Pager 页即使仍处于 STARTED 也不能读系统。 */
    visibleToUser: Boolean = true,
): AppIconEntry<Bitmap>? {
    /** 一个组合绑定只保留一个图标/主色结果，没有全局包名状态表。 */
    val session = remember(packageName, loader) { AppIconUiSession(packageName, loader::load) }
    /** 使用导航目的地生命周期，而非 Activity 存活状态。 */
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // 组合离场或包名切换立即关闭，晚到结果不得填入已废弃会话。
    DisposableEffect(session) { onDispose { session.close() } }
    // 生命周期暂停取消子读取；再次 STARTED 重试未完成项，已完成项直接复用。
    LaunchedEffect(session, lifecycle, visibleToUser) {
        if (visibleToUser) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { session.load() }
    }
    /** 只在前台收集局部结果，颜色与图像始终来自同一份条目。 */
    val entry by session.state.collectAsStateWithLifecycle()
    return entry
}
