package com.cla.clip.master.ui.widget.image

import android.graphics.BitmapFactory
import androidx.compose.ui.unit.IntSize
import coil3.disk.DiskCache
import coil3.request.SuccessResult
import com.cla.clip.base.general.utils.logD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import java.io.IOException

/**
 * 从本次成功请求取得原图宽高：未采样结果直接使用；采样结果只读现有 Coil 缓存的图片头。
 * 不触发下载、不分配原图位图；缓存缺失或头无法识别返回 null，不能将采样尺寸作为原图元信息。
 */
internal suspend fun readOriginalPreviewSize(result: SuccessResult, diskCache: DiskCache?): IntSize? {
    if (!result.isSampled) {
        return IntSize(result.image.width, result.image.height).takeIf { it.width > 0 && it.height > 0 }
    }
    // 成功结果自带的缓存身份与当前请求严格对应，不根据 URL 猜测磁盘文件。
    val cacheKey = result.diskCacheKey ?: return null
    if (diskCache == null) return null
    return withContext(Dispatchers.IO) {
        try {
            diskCache.openSnapshot(cacheKey)?.use { snapshot ->
                // bounds 模式仅解析头部；关闭流与快照后允许 Coil 正常淘汰缓存。
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                diskCache.fileSystem.source(snapshot.data).buffer().inputStream().use { input ->
                    BitmapFactory.decodeStream(input, null, bounds)
                }
                IntSize(bounds.outWidth, bounds.outHeight).takeIf { it.width > 0 && it.height > 0 }
            }
        } catch (error: IOException) {
            // 元信息失败不影响已经显示的图片，只记录低敏异常类型。
            logD("ImagePreview") { "原图尺寸不可用 type=${error.javaClass.simpleName}" }
            null
        }
    }
}
