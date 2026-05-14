package com.cla.clip.master.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.cla.clip.base.general.utils.imageOutputDirToPublicFile
import com.cla.clip.base.general.utils.normalizeImageOutputDir

/**
 * 图片批量下载结果查看入口工具。
 *
 * 用户已经确认文件夹直达在目标设备体验不稳定，因此下载完成后不再尝试 DocumentsUI 或文件管理器，
 * 只打开系统相册入口；如果相册支持 bucket 参数，会尽量进入本次保存目录对应的相册。
 */
object ImageFolderOpenHelper {

    /**
     * 打开图片批量下载结果所在相册。
     *
     * @param context 任意 Context；函数会为非 Activity Context 自动添加 NEW_TASK。
     * @param outputDir 批次记录的相对目录，例如 `DCIM/clipMaster/foo`；为空时直接打开普通相册。
     * @return 打开结果，用于调用方判断系统是否存在可处理图片媒体库的应用。
     */
    fun openDownloadedImageFolder(context: Context, outputDir: String?): ImageFolderOpenResult {
        val normalizedOutputDir = normalizeImageOutputDir(outputDir)
        val galleryCandidates = buildList {
            if (normalizedOutputDir != null) {
                add(openGalleryBucketIntent(context, normalizedOutputDir))
            }
            add(openGalleryIntent())
        }
        return if (galleryCandidates.any { context.tryStartActivity(it) }) {
            ImageFolderOpenResult.Gallery
        } else {
            ImageFolderOpenResult.None
        }
    }

    /**
     * 构建按相册 bucket 定位的图片媒体库 Intent。
     *
     * 这不是标准文件夹协议，但不少相册应用会按 bucketId 打开对应相册；如果不支持，通常会退到普通图片媒体库。
     */
    private fun openGalleryBucketIntent(context: Context, outputDir: String): Intent {
        val bucketId = context.imageOutputDirToPublicFile(outputDir)
            ?.absolutePath
            ?.lowercase()
            ?.hashCode()
            ?.toString()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon().apply {
            if (bucketId != null) {
                appendQueryParameter("bucketId", bucketId)
            }
        }.build()
        return Intent(Intent.ACTION_VIEW).apply {
            // 必须用 setDataAndType；单独设置 type 会清掉前面设置的 data，导致相册仍打开默认图片入口。
            setDataAndType(uri, "vnd.android.cursor.dir/image")
        }
    }

    /**
     * 构建系统图片媒体库入口。
     *
     * 目录级 Intent 都不可用时，进入相册至少能看到刚发布到 MediaStore 的图片；这是最稳定但不够精准的兜底。
     */
    private fun openGalleryIntent(): Intent {
        return Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
    }

    /** 尝试启动外部 Activity；失败只返回 false，让上层继续尝试下一种目录/相册入口。 */
    private fun Context.tryStartActivity(intent: Intent): Boolean {
        return runCatching {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    /**
     * 图片保存位置打开结果。
     *
     * Gallery 表示已打开相册或对应相册 bucket；None 表示没有任何可用应用能处理图片媒体库。
     */
    enum class ImageFolderOpenResult {
        /** 已打开相册或对应相册 bucket 作为查看入口。 */
        Gallery,

        /** 没有任何可用应用能处理相册查看。 */
        None
    }
}
