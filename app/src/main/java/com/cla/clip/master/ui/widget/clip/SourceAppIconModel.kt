package com.cla.clip.master.ui.widget.clip

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.cla.clip.base.general.utils.isUsableCachedIconFile
import coil3.request.ImageRequest
import java.io.File

/**
 * 构建来源 App 图标的 Coil 请求模型。
 *
 * @param appIconPath 来源 App 图标缓存路径；为空时返回 null 让 AsyncImage 显示兜底图标。
 * @param appIconHash 来源 App 图标内容 hash；同一路径被覆盖时用它打散 Coil 缓存。
 */
@Composable
internal fun rememberSourceAppIconModel(appIconPath: String?, appIconHash: String?): Any? {
    /** 当前 Compose Context，用于构建 Coil ImageRequest。 */
    val context = LocalContext.current
    return remember(appIconPath, appIconHash, context) {
        /** 来源 App 图标文件；路径为空、文件缺失、空文件或不可读时让 AsyncImage 使用兜底图标。 */
        val iconFile = appIconPath
            ?.takeIf { path -> path.isNotBlank() }
            ?.let { path -> File(path) }
            ?.takeIf { file -> file.isUsableCachedIconFile() }
            ?: return@remember null
        /** 同一路径图标被覆盖时用于刷新 Coil 记忆和磁盘缓存的稳定 key。 */
        val cacheKey = buildSourceAppIconCacheKey(iconFile.absolutePath, appIconHash)
        ImageRequest.Builder(context)
            .data(iconFile)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .build()
    }
}

/**
 * 构建来源 App 图标的 Coil 缓存 key。
 *
 * @param appIconPath 来源 App 图标缓存路径。
 * @param appIconHash 来源 App 图标内容 hash；同一路径被覆盖后它会变化并触发 UI 刷新。
 */
internal fun buildSourceAppIconCacheKey(appIconPath: String, appIconHash: String?): String {
    return "$appIconPath:${appIconHash.orEmpty()}"
}
