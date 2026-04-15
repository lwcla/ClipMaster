package com.cla.clip.master.utils

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.cla.clip.base.general.R
import com.cla.clip.base.general.entity.ClipCaptureEntity
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.entity.LiveEvent
import com.cla.clip.base.general.repository.ClipDao
import com.cla.clip.base.general.utils.ApplicationScope
import com.cla.clip.base.general.utils.LinkUtils
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logI
import com.cla.clip.master.entity.ReadClipEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class ClipHelper @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val clipDao: dagger.Lazy<ClipDao>,
    private val notificationHelper: dagger.Lazy<NotificationHelper>,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "ClipHelper"
    }

    private val _readClipFlow = MutableStateFlow<ReadClipEvent?>(null)
    private val readClipFlow = _readClipFlow.filterNotNull().mapNotNull {
        it.hasFocus?.content == true && it.resume?.content == true
    }.onEach {
        readNow()
    }.stateIn(
        CoroutineScope(scope.coroutineContext + Dispatchers.IO),
        SharingStarted.Eagerly,
        null
    )

    val clipboardManager by lazy { appContext.getSystemService(ClipboardManager::class.java) }

    private var lastClipContent = AtomicReference<String>()
    private var lastSourcePackageName = AtomicReference<String>()

    fun delete(clip: ClipShowEntity) {
        // 想了想，用户已经手动删除了这条记录，那么就不应该再次触发保存，这里的代码暂时注释

        // 如果删除了的剪贴板内容和当前剪贴板内容一致，则清空当前剪贴板内容，避免重复剪贴数据时不会再次保存
//        if (lastClipContent.get() == clip.content) {
//            lastClipContent.set(null)
//            lastSourcePackageName.set(null)
//        }
    }

    /**
     * 如果每次onWindowFocusChanged都读取剪贴板，就会导致删除第一条剪贴数据的弹窗消失之后，又会触发一次onWindowFocusChanged，导致剪贴板内容又被读取了一次，重复保存了第一条剪贴数据
     * 所以增加了resume参数，只有在resume=true时才读取剪贴板，其他时候只是更新hasFocus状态，不读取剪贴板
     */
    fun readNow(resume: Boolean? = null, hasFocus: Boolean? = null) {
        _readClipFlow.update { last ->
            val resume2 = resume?.let { LiveEvent(it) } ?: last?.resume
            val hasFocus2 = hasFocus?.let { LiveEvent(it) } ?: last?.hasFocus
            ReadClipEvent(resume = resume2, hasFocus = hasFocus2)
        }
    }

    private suspend fun readNow() {
        // 切换到前台时，读取一次剪贴板
        val clip = runCatching { clipboardManager.primaryClip?.getItemAt(0) }.getOrNull()
        if (clip == null) {
            return
        }

        logI(TAG) { "readNow: 回到前台时读取一次剪贴板" }
        processClip(
            item = clip,
            packageName = null,
            appName = null,
            iconPath = null,
            iconColor = null,
            iconHash = null
        )
    }

    /** 处理新的剪贴板内容 */
    suspend fun processClip(
        item: android.content.ClipData.Item,
        packageName: String?,
        appName: String?,
        iconPath: String?,
        iconColor: Int?,
        iconHash: String?,
    ) {
        // 保存剪贴板内容
        val contentUri = item.uri
        val contentText = item.text?.toString()

        if (!lastClipContent.get().isNullOrBlank()) {
            if (contentText == lastClipContent.get() && (packageName == null || packageName == lastSourcePackageName.get())) {
                logD(TAG) { "processClip: 111 contentText=${contentText} packageName=${packageName} 和上一条是重复的，不要重复保存" }
                return
            }
        }

        lastClipContent.set(contentText)
        lastSourcePackageName.set(packageName)

        val lastClip = clipDao.get().loadLastClip()
        if (lastClip != null) {
            if (contentText == lastClip.content && (packageName == null || lastClip.sourceAppPackage == packageName)) {
                logD(TAG) { "processClip: 222 contentText=${contentText} packageName=${packageName} 和上一条是重复的，不要重复保存" }
                return
            }
        }

        val clipContent = when {
            // 处理图片类型
//            contentUri != null && contentUri.toString().startsWith("content://") -> {
//                saveImageAndGetPath(contentUri)
//            }

            else -> contentText
        }?.trim()

        if (clipContent.isNullOrBlank()) {
            return
        }


        // 对于图片类型，启动OCR任务
//            if (clipType == ClipType.IMAGE) {
//                // TODO: 实现图片OCR逻辑
//                // OcrProcessingWorker.enqueue(this@ClipboardService, clipContent, newClip.id)
//            }

        val extractedLink = LinkUtils.extractFirstPreviewableUrl(contentText)
        val linkMeta = if (!extractedLink.isNullOrBlank()) {
            val history = clipDao.get().loadLinkPreview(extractedLink)
            if (!history?.imageUrl.isNullOrBlank()) {
                logD(TAG) { "processClip 使用数据库中的链接数据 extractedLink=$extractedLink" }
                // 避免重复解析链接
                LinkMeta(history.title, history.description, history.imageUrl, history.siteName)
            } else {
                logD(TAG) { "processClip 去解析链接 extractedLink=$extractedLink" }
                LinkMetaParser.parse(extractedLink)
            }
        } else {
            null
        }

        val captureEntity = ClipCaptureEntity(
            content = clipContent,
            timestamp = System.currentTimeMillis(),
            sourcePackage = packageName ?: "",
            sourceAppName = appName ?: "",
            sourceAppIconPath = iconPath,
            sourcePrimaryColor = iconColor,
            sourceAppIconHash = iconHash,
            link = extractedLink,
            linkTitle = linkMeta?.title,
            linkDescription = linkMeta?.description,
            linkImageUrl = linkMeta?.imageUrl,
            linkSiteName = linkMeta?.siteName,
        )

        logI(TAG) { "processClip: isLink=${!extractedLink.isNullOrBlank()} captureEntity=$captureEntity" }

        // 保存到数据库
        val clipId = clipDao.get().addNewClip(captureEntity)

        logD(TAG) { "processClip: 保存到数据库 clipId=${clipId}" }
        notificationHelper.get().notifyClipUpdate(
            title = "$appName ${appContext.getString(R.string.base_general_it_was_written_into_the_clipboard)}",
            content = "${appContext.getString(R.string.base_general_content)}${clipContent}",
            clipId = clipId
        )
    }

    /** 保存剪贴板中的图片，并返回保存路径 */
    private fun saveImageAndGetPath(imageUri: Uri): String {
        val imageDir = File(appContext.filesDir, "clip_images")
        if (!imageDir.exists()) {
            imageDir.mkdirs()
        }

        val fileName = "clip_img_${UUID.randomUUID()}.png"
        val imageFile = File(imageDir, fileName)

        return try {
            appContext.contentResolver.openInputStream(imageUri)?.use { input ->
                FileOutputStream(imageFile).use { output ->
                    input.copyTo(output)
                }
            }

            // 返回ContentProvider URI，确保应用内可访问
            val fileUri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                imageFile
            )

            fileUri.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }
}