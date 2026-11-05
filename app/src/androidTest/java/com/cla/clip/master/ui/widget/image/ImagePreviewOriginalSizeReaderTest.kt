package com.cla.clip.master.ui.widget.image

import android.graphics.Bitmap
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.asImage
import coil3.disk.DiskCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 原图元信息读取使用隔离缓存和合成图片，不访问网络或用户媒体。 */
@RunWith(AndroidJUnit4::class)
class ImagePreviewOriginalSizeReaderTest {
    /** 原图头中的宽高不能被缩小后的预览位图尺寸替代，缓存丢失时也不能误报。 */
    @Test fun sampledImageReadsOriginalBoundsAndMissingCacheStaysUnknown() = runBlocking {
        // 测试只写宿主专用缓存目录，和应用正式图片缓存隔离。
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // 每轮使用唯一目录，避免复用上一轮缓存内容。
        val directory = File(context.cacheDir, "preview-size-${System.nanoTime()}")
        // 缓存容量仅用于小型合成 PNG，不使用应用正式缓存。
        val cache = DiskCache.Builder().directory(directory.toOkioPath()).maxSizeBytes(1_048_576).build()
        // 编码内容是 80×60，模拟 Coil 采样结果仅有 16×12。
        val original = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        // 小位图代表已采样结果，用于确认不能直接回填其宽高。
        val sampled = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        try {
            // 写入真实 PNG 头，验证 Android bounds 解码而非文件名或结果尺寸猜测。
            val editor = requireNotNull(cache.openEditor("fixture"))
            editor.data.toFile().outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
            editor.commit()
            // 成功结果关联编码原图缓存，但自身仅持有采样图片。
            val result = SuccessResult(
                image = sampled.asImage(), request = ImageRequest.Builder(context).data("fixture").build(),
                diskCacheKey = "fixture", isSampled = true,
            )
            assertEquals(IntSize(80, 60), readOriginalPreviewSize(result, cache))
            cache.remove("fixture")
            assertNull(readOriginalPreviewSize(result, cache))
            assertEquals(IntSize(16, 12), readOriginalPreviewSize(result.copy(isSampled = false), null))
        } finally {
            cache.clear()
            cache.shutdown()
            original.recycle()
            sampled.recycle()
        }
    }
}
