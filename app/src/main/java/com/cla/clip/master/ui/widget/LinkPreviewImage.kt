package com.cla.clip.master.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest

/** 只作用于自动链接封面；没有宿主提供已提交设置时默认禁止网络。 */
internal val LocalLinkPreviewNetworkAllowed = staticCompositionLocalOf { false }

/**
 * 自动链接封面统一边界；关闭时仍可读取内存、磁盘及本地文件缓存。
 * 许可变化重建图片组合以取消旧请求；主动媒体提取/下载的图片组件不使用此边界。
 */
@Composable
internal fun LinkPreviewImage(model: String, modifier: Modifier = Modifier) {
    /** 应用宿主按生命周期订阅得到的已提交值。 */
    val allowNetwork = LocalLinkPreviewNetworkAllowed.current
    /** 图片请求依赖当前 UI Context，不持久保存 Activity。 */
    val context = LocalContext.current
    /** 缓存 key 沿用原地址，关闭只禁止网络，不清空已有预览缓存。 */
    val request = remember(context, model, allowNetwork) {
        ImageRequest.Builder(context).data(model)
            .networkCachePolicy(if (allowNetwork) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .build()
    }
    key(allowNetwork) {
        AsyncImage(model = request, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    }
}
