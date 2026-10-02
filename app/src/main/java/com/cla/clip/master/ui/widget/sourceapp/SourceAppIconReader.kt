package com.cla.clip.master.ui.widget.sourceapp

import android.content.Context
import android.graphics.drawable.Drawable
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.cla.clip.base.general.utils.isUsableCachedIconFile
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logW
import com.cla.clip.master.ui.widget.clip.buildSourceAppIconCacheKey
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 单个来源图标的只读平台适配，调用方在 IO 调度器执行。
 * 缓存与系统读取均以 null 表示不可用，取消向上传播；不修改来源表或触发整表安装应用扫描。
 */
internal class SourceAppIconReader(
    /** 应用级 Context，仅用于资源转换与 PackageManager，避免持有页面 Activity。 */
    private val context: Context,
    /** 项目共享 Coil 实例，复用既有解码及图标版本缓存。 */
    private val imageLoader: ImageLoader,
) {
    /** 仅用于低敏失败诊断的职责标签，不记录包名、图标路径或来源名称。 */
    private val logTag = "SourceAppIconReader"

    /** 文件不可用或解码失败返回 null，供顺序协作者继续读取系统图标。 */
    suspend fun readCached(
        /** 来源表保存的缓存路径，调用方已排除空路径。 */
        iconPath: String,
        /** 同路径更新的内容版本，与剪贴卡片使用相同缓存键语义。 */
        iconHash: String?,
    ): Drawable? {
        return try {
            currentCoroutineContext().ensureActive()
            /** 复用缓存文件校验，不把目录、空文件或不可读文件交给解码器。 */
            val iconFile = File(iconPath)
            if (!iconFile.isUsableCachedIconFile()) return null
            /** 图标路径与 hash 的稳定缓存身份，避免复用旧版本解码结果。 */
            val cacheKey = buildSourceAppIconCacheKey(iconPath, iconHash)
            /** 先完成解码再判定缓存命中，不能只以文件存在决定是否阻止系统兜底。 */
            val request = ImageRequest.Builder(context)
                .data(iconFile)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                .build()
            /** Coil 真实解码结果；ErrorResult 不将 placeholder 当作成功缓存。 */
            val result = imageLoader.execute(request)
            currentCoroutineContext().ensureActive()
            if (result is SuccessResult) {
                result.image.asDrawable(context.resources)
            } else {
                logW(logTag) {
                    "来源图标缓存解码失败 reasonCode=cached_icon_decode_failed type=${(result as? ErrorResult)?.throwable?.javaClass?.simpleName}"
                }
                null
            }
        } catch (error: CancellationException) {
            // 菜单离开后的取消不能当作缓存损坏，更不能继续查询系统。
            throw error
        } catch (error: Exception) {
            logW(logTag) { "来源图标缓存读取失败 reasonCode=cached_icon_read_failed type=${error.javaClass.simpleName}" }
            null
        }
    }

    /** 按单个包名读取 Application 图标；卸载、不可见或资源读取失败返回 null。 */
    suspend fun readSystem(
        /** 来源表的稳定非空包名，不使用显示名称查询。 */
        packageName: String,
    ): Drawable? {
        return try {
            currentCoroutineContext().ensureActive()
            /** 只查询当前来源，不加载安装应用列表或借用 Shizuku 特权进程。 */
            val icon = context.packageManager.getApplicationIcon(packageName)
            // Binder 调用不可强制中断，返回后再次检查取消，避免已离开的行发布结果。
            currentCoroutineContext().ensureActive()
            icon
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logD(logTag) { "来源系统图标不可用 reasonCode=system_icon_unavailable type=${error.javaClass.simpleName}" }
            null
        }
    }
}
