package com.cla.clip.master.sourceapp

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import com.cla.clip.base.general.utils.extractUsableColor
import com.cla.clip.base.general.utils.iconBitmap
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logW
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 所有应用图标入口共用的主进程管理器，只持应用 Context 和有界小图缓存。 */
@Singleton
class AppIconManager @Inject constructor(
    /** 资源和 PackageManager 的应用级上下文，不持 Activity。 */
    @param:ApplicationContext private val context: Context,
) {
    /** 纯缓存负责同包去重、容量和失败冷却。 */
    private val cache = AppIconCache<Bitmap>(read = ::readSystem)
    /** 只在内存压力下清缓存，不因包变更或资源配置变化主动更新图标。 */
    private val callbacks = object : ComponentCallbacks2 {
        /** 配置切换沿用本次进程快照，系统图标更新等待后续缓存未命中读取。 */
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        /** 系统明确内存不足时允许淘汰全部 Manager 缓存。 */
        override fun onLowMemory() { cache.clear() }
        /** 任何 trim 信号都允许放弃可重建缓存，已展示对象仍有效。 */
        override fun onTrimMemory(level: Int) { cache.clear() }
    }

    /** 仅注册内存回调；不监听包变更，进程重启后按需读取最新图标。 */
    init {
        context.registerComponentCallbacks(callbacks)
    }

    /** IO 线程取得最终快照；UI 不单独观察图标缓存变化。 */
    internal suspend fun load(packageName: String): AppIconEntry<Bitmap> = withContext(Dispatchers.IO) { cache.load(packageName) }

    /** Launcher 优先保持旧来源图标语义，无入口时回退 Application；失败使用默认展示。 */
    private suspend fun readSystem(packageName: String): AppIconEntry<Bitmap> {
        return try {
            currentCoroutineContext().ensureActive()
            /** 只查询当前包桌面入口，不扫描全部安装应用。 */
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
            /** 多入口按 Activity 名稳定选择，避免不同入口使缓存抖动。 */
            val launcher = context.packageManager.queryIntentActivities(intent, 0)
                .sortedBy { it.activityInfo?.name.orEmpty() }.firstOrNull()
            // 不可中断的系统调用返回后，停止已取消请求的后续资源读取。
            currentCoroutineContext().ensureActive()
            /** 桌面入口资源失败允许继续尝试 Application，不阻断整条记录。 */
            val launcherIcon = try {
                launcher?.loadIcon(context.packageManager)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            currentCoroutineContext().ensureActive()
            /** Android 30 起可识别系统默认图标；避免把它当成真实图标取色。 */
            val validLauncher = launcherIcon?.takeUnless {
                Build.VERSION.SDK_INT >= 30 && context.packageManager.isDefaultApplicationIcon(it)
            }
            /** Application 兜底同样在 IO 读取系统资源。 */
            val drawable = validLauncher ?: context.packageManager.getApplicationIcon(packageName)
            currentCoroutineContext().ensureActive()
            if (Build.VERSION.SDK_INT >= 30 && context.packageManager.isDefaultApplicationIcon(drawable)) {
                logD("AppIconManager") { "来源图标不可用 reasonCode=system_default_icon" }
                return AppIconEntry(null, null)
            }
            currentCoroutineContext().ensureActive()
            /** 限定到现有最大 74 像素，不保留系统大 Drawable。 */
            val bitmap = drawable.iconBitmap()
            /** 真实小图的可空颜色，不缓存主题兜底。 */
            val color = bitmap?.extractUsableColor()
            if (bitmap != null && color == null) {
                logW("AppIconManager") { "来源主色不可用 reasonCode=color_unavailable" }
            }
            currentCoroutineContext().ensureActive()
            AppIconEntry(bitmap, color)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // 被取消的系统调用抛普通异常时，仍传播取消，不记录失败或负缓存。
            currentCoroutineContext().ensureActive()
            logD("AppIconManager") { "来源图标不可用 reasonCode=system_icon_unavailable type=${error.javaClass.simpleName}" }
            AppIconEntry(null, null)
        }
    }
}
