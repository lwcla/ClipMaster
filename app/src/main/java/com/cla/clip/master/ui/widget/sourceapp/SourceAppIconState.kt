package com.cla.clip.master.ui.widget.sourceapp

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 为可见来源项异步加载缓存或系统图标，加载中/最终不可用返回 null 让调用方显示默认图标。
 * 参数变化重启，行或菜单离开组合时取消，不在重组线程执行文件或系统读取，不保存跨页面状态。
 */
@Composable
internal fun rememberSourceAppIcon(
    /** 稳定来源身份，空包名仍可展示已有缓存但不会查询系统。 */
    packageName: String,
    /** 页面已收集的缓存路径，本组件不观察数据库。 */
    iconPath: String?,
    /** 缓存更新版本，变化时重新加载当前来源图标。 */
    iconHash: String?,
): Drawable? {
    /** 只持有应用上下文，不随页面 Activity 保留平台读取器。 */
    val context = LocalContext.current.applicationContext
    /** 复用全局 Coil 配置与缓存，平台读取器仅在应用上下文变化时重建。 */
    val reader = remember(context) { SourceAppIconReader(context, SingletonImageLoader.get(context)) }
    /** 顺序协作者只连接两个读取边界，不持有页面状态或数据库。 */
    val resolver = remember(reader) { SourceAppIconResolver(reader::readCached, reader::readSystem) }
    /** 当前组合范围内图标；稳定身份、路径或 hash 变化会取消旧任务并启动新的 IO 读取。 */
    val icon by produceState<Drawable?>(null, resolver, packageName, iconPath, iconHash) {
        // 参数变化先清理旧结果，避免在新路径读取期间展示上一版本图标。
        value = null
        value = withContext(Dispatchers.IO) { resolver.load(packageName, iconPath, iconHash) }
    }
    return icon
}
