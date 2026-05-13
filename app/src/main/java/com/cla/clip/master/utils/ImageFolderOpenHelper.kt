package com.cla.clip.master.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.cla.clip.base.general.R
import com.cla.clip.base.general.utils.imageOutputDirToPublicFile
import com.cla.clip.base.general.utils.normalizeImageOutputDir

/**
 * 图片批量下载目录打开工具。
 *
 * Android 没有稳定统一的“打开公共媒体文件夹”协议，不同文件管理器对目录 URI、DocumentsUI 初始目录和相册入口支持各不相同。
 * 这里把多种 Intent 按成功率排序集中维护，页面完成态和通知点击都复用同一套兜底链路，避免各处打开结果不一致。
 */
object ImageFolderOpenHelper {

    /** 外部存储 DocumentsProvider authority，用于把公共目录转换成可被 DocumentsUI 理解的 tree/document URI。 */
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** 外部主存储卷 id，公共相册目录位于这个卷下。 */
    private const val PRIMARY_VOLUME_ID = "primary"

    /**
     * 打开图片批量下载目录。
     *
     * @param context 任意 Context；函数会为非 Activity Context 自动添加 NEW_TASK。
     * @param outputDir 批次记录的相对目录，例如 `DCIM/clipMaster/foo`；为空时直接进入相册兜底。
     * @return true 表示至少有一个外部入口已成功启动，false 表示设备没有可用应用可打开。
     */
    fun openDownloadedImageFolder(context: Context, outputDir: String?): Boolean {
        val normalizedOutputDir = normalizeImageOutputDir(outputDir)
        val candidates = buildList {
            if (normalizedOutputDir != null) {
                add(openDocumentsTreeIntent(normalizedOutputDir))
                add(openDocumentsRootWithInitialUriIntent(normalizedOutputDir))
                openFileManagerIntent(context, normalizedOutputDir)?.let(::add)
            }
            add(openGalleryIntent())
            add(openImagePickerIntent(context))
        }

        return candidates.any { intent ->
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
        }
    }

    /**
     * 构建直接查看目标目录的 DocumentsProvider URI。
     *
     * 这是最接近“打开对应文件夹”的路径；部分系统 DocumentsUI 会直接进入目标目录，部分第三方 ROM 可能拒绝该 URI，
     * 因此调用方仍需要继续尝试后续兜底 Intent。
     */
    private fun openDocumentsTreeIntent(outputDir: String): Intent {
        val treeUri = externalStorageTreeUri(outputDir)
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, externalStorageDocumentId(outputDir))
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(documentUri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * 构建 DocumentsUI 初始目录 Intent。
     *
     * 当系统不接受直接查看目录 URI 时，ACTION_OPEN_DOCUMENT_TREE 仍可能把文件选择器定位到目标目录，让用户少点几层目录。
     */
    private fun openDocumentsRootWithInitialUriIntent(outputDir: String): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, externalStorageTreeUri(outputDir))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * 构建旧式文件管理器目录 Intent。
     *
     * Android 10 以下或部分厂商文件管理器仍支持 `file://` 目录打开；Android 10+ 不能依赖真实路径访问，所以只作为兜底候选。
     */
    private fun openFileManagerIntent(context: Context, outputDir: String): Intent? {
        val dir = context.imageOutputDirToPublicFile(outputDir) ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.fromFile(dir), DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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

    /**
     * 构建图片查看入口选择器。
     *
     * 某些设备没有相册应用但有文件管理器或第三方图片应用；选择器作为最后兜底，不要求能定位到本批次目录。
     */
    private fun openImagePickerIntent(context: Context): Intent {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = "image/*"
        }
        return Intent.createChooser(intent, context.getString(R.string.base_general_open_image_folder_chooser))
    }

    /**
     * 将相对保存目录转换成 DocumentsProvider tree URI。
     *
     * 路径中的斜杠需要作为 document id 的一部分参与编码，不能拆成普通 path segment；否则 DocumentsUI 无法识别目标目录。
     */
    private fun externalStorageTreeUri(outputDir: String): Uri {
        return DocumentsContract.buildTreeDocumentUri(
            EXTERNAL_STORAGE_AUTHORITY,
            externalStorageDocumentId(outputDir)
        )
    }

    /**
     * 生成外部主存储的 document id。
     *
     * DocumentsProvider 使用 `primary:DCIM/...` 表示公共目录；目录已经过 normalizeImageOutputDir 清洗，避免前导斜杠导致错误定位。
     */
    private fun externalStorageDocumentId(outputDir: String): String {
        return "$PRIMARY_VOLUME_ID:${outputDir.trimStart('/')}"
    }
}
