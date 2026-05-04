package com.cla.clip.master.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.cla.clip.base.general.R
import com.cla.clip.base.general.entity.ClipCaptureEntity
import com.cla.clip.base.general.entity.LiveEvent
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.base.general.utils.ApplicationScope
import com.cla.clip.base.general.utils.LinkUtils
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logI
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class ClipHelper @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val clipRepository: dagger.Lazy<ClipRepository>,
    private val notificationHelper: dagger.Lazy<NotificationHelper>,
    private val linkMetaParser: LinkMetaParser,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "ClipHelper"
    }

    val clipboardManager by lazy { appContext.getSystemService(ClipboardManager::class.java) }

    private var lastClipContent = AtomicReference<String>()

    @Volatile
    private var lastResume: LiveEvent<Boolean>? = null

    @Volatile
    private var lastHasFocus: Boolean? = null

    /**
     * 如果每次onWindowFocusChanged都读取剪贴板，就会导致删除第一条剪贴数据的弹窗消失之后，又会触发一次onWindowFocusChanged，导致剪贴板内容又被读取了一次，重复保存了第一条剪贴数据
     * 所以增加了resume参数，只有在resume=true时才读取剪贴板，其他时候只是更新hasFocus状态，不读取剪贴板
     */
    fun readNow(resume: Boolean? = null, hasFocus: Boolean? = null) {
        val resume2 = resume?.let { LiveEvent(it) } ?: lastResume
        val hasFocus2 = hasFocus ?: lastHasFocus

        lastResume = resume2
        lastHasFocus = hasFocus2

        if (hasFocus2 == true && resume2?.content == true) {
            scope.launch(Dispatchers.IO) {
                readNow()
            }
        }
    }

    private suspend fun readNow() {
        // 切换到前台时，读取一次剪贴板
        val clip = runCatching { clipboardManager.primaryClip?.getItemAt(0) }.getOrNull()
        if (clip == null) {
            return
        }

        // 复制内容到剪贴板之后马上拉起app，可能会在shizuku和MainActivity同时触发读取剪贴板的逻辑，导致重复保存，所以这里增加一个短暂的延迟，判断一下保存的剪贴板内容是否是一样的，一样的话就不重复保存
        delay(500)

        val contentText = clip.text?.toString()
        if (!lastClipContent.get().isNullOrBlank() && contentText == lastClipContent.get()) {
            logD(TAG) { "readNow: contentText=${contentText} 和上一条是重复的，不要重复保存" }
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
        item: ClipData.Item,
        packageName: String?,
        appName: String?,
        iconPath: String?,
        iconColor: Int?,
        iconHash: String?,
    ) = withContext(Dispatchers.IO) {
        // 保存剪贴板内容
        val contentUri = item.uri
        val contentText = item.text?.toString()

        lastClipContent.set(contentText)

        val lastClip = clipRepository.get().loadLastClip()
        if (lastClip != null) {
            if (contentText == lastClip.content && (packageName == null || lastClip.sourceAppPackage == packageName)) {
                logD(TAG) { "processClip: 222 contentText=${contentText} packageName=${packageName} 和上一条是重复的，不要重复保存" }
                return@withContext
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
            return@withContext
        }


        // 对于图片类型，启动OCR任务
//            if (clipType == ClipType.IMAGE) {
//                // TODO: 实现图片OCR逻辑
//                // OcrProcessingWorker.enqueue(this@ClipboardService, clipContent, newClip.id)
//            }

        suspend fun save(link: String?, linkMeta: LinkMeta?) {
            val captureEntity = ClipCaptureEntity(
                content = clipContent,
                timestamp = System.currentTimeMillis(),
                sourcePackage = packageName ?: "",
                sourceAppName = appName ?: "",
                sourceAppIconPath = iconPath,
                sourcePrimaryColor = iconColor,
                sourceAppIconHash = iconHash,
                link = link,
                linkTitle = linkMeta?.title,
                linkDescription = linkMeta?.description,
                linkImageUrl = linkMeta?.imageUrl,
                linkSiteName = linkMeta?.siteName,
            )

            logI(TAG) { "processClip: isLink=${!link.isNullOrBlank()} captureEntity=$captureEntity" }

            // 保存到数据库
            val clipId = clipRepository.get().addNewClip(captureEntity)

            logD(TAG) { "processClip: 保存到数据库 clipId=${clipId}" }
            notificationHelper.get().notifyClipUpdate(
                title = "$appName ${appContext.getString(R.string.base_general_it_was_written_into_the_clipboard)}",
                content = "${appContext.getString(R.string.base_general_content)}${clipContent}",
                clipId = clipId
            )
        }

        val extractedLink = LinkUtils.extractFirstUrl(clipContent)
        if (extractedLink.isNullOrBlank()) {
            save(extractedLink, null)
            return@withContext
        }

        if (LinkUtils.isImageUrl(extractedLink)) {
            save(
                extractedLink,
                LinkMeta(
                    title = null,
                    description = null,
                    imageUrl = extractedLink,
                    siteName = null,
                )
            )
            return@withContext
        }

        if (LinkUtils.isDownloadableMediaUrl(clipContent)) {
            // 纯视频链接，这种是拿不到预览图的，就不去解析了
            save(extractedLink, null)
            return@withContext
        }

        val history = clipRepository.get().loadLinkPreview(extractedLink)
        if (!history?.imageUrl.isNullOrBlank()) {
            logD(TAG) { "processClip 使用数据库中的链接数据 extractedLink=$extractedLink" }
            // 避免重复解析链接
            val linkMeta = LinkMeta(history.title, history.description, history.imageUrl, history.siteName)
            save(extractedLink, linkMeta)
            return@withContext
        }

        // 解析链接在网络比较差的情况下，耗时长，所以先保存一次剪贴数据，等到链接解析完成之后再更新一次剪贴数据，
        // 这样用户就能第一时间看到保存的剪贴数据了，而不是等链接解析完成之后才看到保存的剪贴数据
        save(extractedLink, null)

        logD(TAG) { "processClip 去解析链接 extractedLink=$extractedLink" }
        // 解析链接可能会比较慢，所以放在协程里，解析完成之后再保存数据
        // 避免网络比较差的情况下，需要很长时间才能看到保存的剪贴数据
        val deferred = async {
            val linkMeta = linkMetaParser.parse(extractedLink)
            logD(TAG) { "processClip 链接解析结果 linkMeta=$linkMeta" }
            linkMeta
        }

        save(extractedLink, deferred.await())
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
