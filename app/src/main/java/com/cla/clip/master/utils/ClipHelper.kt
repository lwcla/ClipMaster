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
import com.cla.clip.base.general.repository.ClipSaveResult
import com.cla.clip.base.general.repository.shouldSkipConsecutiveDuplicateClip
import com.cla.clip.base.general.utils.ApplicationScope
import com.cla.clip.base.general.utils.LinkUtils
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logI
import com.cla.clip.master.work.BackupAutoScheduler
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


/**
 * 剪贴板读取与入库协调器。
 *
 * 负责从前台系统剪贴板读取或 Shizuku Provider payload 接收内容，做重复过滤、链接识别、预览信息解析和通知发送。
 * 该类是应用级单例，内部状态需要能承受 MainActivity 与 Shizuku 服务几乎同时上报同一条剪贴内容。
 */
@Singleton
class ClipHelper @Inject constructor(
    /** 应用级 Context，用于访问 ClipboardManager、文件目录和字符串资源。 */
    @param:ApplicationContext private val appContext: Context,

    /** 剪贴仓库延迟注入，避免单例创建时提前初始化数据库。 */
    private val clipRepository: dagger.Lazy<ClipRepository>,

    /** 通知工具延迟注入，只有成功保存剪贴记录后才需要创建通知。 */
    private val notificationHelper: dagger.Lazy<NotificationHelper>,

    /** 链接预览解析器，用于异步补齐标题、描述和封面图。 */
    private val linkMetaParser: LinkMetaParser,

    /** 应用级协程作用域，保证后台剪贴处理不依赖单个 Activity 生命周期。 */
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "ClipHelper"
    }

    /** 系统剪贴板管理器，懒加载避免启动阶段提前触碰系统服务。 */
    val clipboardManager by lazy { appContext.getSystemService(ClipboardManager::class.java) }

    /**
     * 最近一次处理的剪贴文本。
     *
     * 使用原子引用处理 MainActivity 前台读取和 Shizuku 回调短时间并发到达的重复过滤。
     */
    private var lastClipContent = AtomicReference<String>()

    /** 最近一次 Activity resume 事件，LiveEvent 用于避免同一次恢复事件被焦点变化重复消费。 */
    @Volatile
    private var lastResume: LiveEvent<Boolean>? = null

    /** 最近一次窗口焦点状态；需要和 resume 状态同时满足才读取剪贴板。 */
    @Volatile
    private var lastHasFocus: Boolean? = null

    /**
     * 根据 Activity 生命周期和焦点变化决定是否立即读取剪贴板。
     *
     * 如果每次 onWindowFocusChanged 都读取剪贴板，删除弹窗消失后会再次触发焦点变化并重复保存同一条内容；
     * 因此只有“刚 resume 且有焦点”同时满足时才读取，其他调用只刷新最近状态。
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

    /**
     * 实际执行一次前台剪贴板读取。
     *
     * 这里延迟 500ms 是为了等待 Shizuku 回调可能先完成入库，再通过 lastClipContent 去重，降低双通道重复保存概率。
     */
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
            logD(TAG) { "readNow: textLength=${contentText?.length} 和上一条重复，跳过保存" }
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

    /**
     * 处理新的剪贴板内容。
     *
     * 输入可能来自前台系统剪贴板读取或 Shizuku Provider payload；保存前会去重、提取首个链接、复用历史链接预览并异步补齐元信息。
     * 图标路径、主色和 Hash 由调用方按来源 App 传入，用于列表卡片展示和缓存命中判断。
     */
    suspend fun processClip(
        item: ClipData.Item,
        packageName: String?,
        appName: String?,
        iconPath: String?,
        iconColor: Int?,
        iconHash: String?,
        capturedAtMillis: Long = System.currentTimeMillis(),
    ): ClipProcessResult {
        /** 系统剪贴 item 中的普通文本；为空时由文本入口统一按空内容处理。 */
        val contentText = item.text?.toString()
        return processClipText(
            contentText = contentText,
            packageName = packageName,
            appName = appName,
            iconPath = iconPath,
            iconColor = iconColor,
            iconHash = iconHash,
            capturedAtMillis = capturedAtMillis
        )
    }

    /**
     * 处理已经提取成文本的剪贴板内容。
     *
     * Shizuku Provider payload 和系统 `ClipData.Item` 都会委托到这里，确保去重、链接解析、备份 dirty 和通知语义一致。
     */
    suspend fun processClipText(
        contentText: String?,
        packageName: String?,
        appName: String?,
        iconPath: String?,
        iconColor: Int?,
        iconHash: String?,
        capturedAtMillis: Long = System.currentTimeMillis(),
    ): ClipProcessResult = withContext(Dispatchers.IO) {
        /** 原始文本去掉首尾空白后的可保存内容；为空时不制造数据库记录。 */
        val clipContent = contentText?.trim()

        lastClipContent.set(clipContent)

        if (clipContent.isNullOrBlank()) {
            return@withContext ClipProcessResult.DuplicateOrEmpty
        }

        /** 数据库中最后一条剪贴记录，用于沿用现有“连续重复内容跳过保存”的语义。 */
        val lastClip = clipRepository.get().loadLastClip()
        if (
            shouldSkipConsecutiveDuplicateClip(
                currentContent = clipContent,
                currentSourcePackage = packageName,
                currentSourceAppName = appName,
                lastClip = lastClip
            )
        ) {
            logD(TAG) {
                "processClipText: 与上一条重复，跳过保存 textLength=${clipContent.length} packageName=${packageName}"
            }
            return@withContext ClipProcessResult.DuplicateOrEmpty
        }

        /**
         * 将当前剪贴内容保存或更新到数据库。
         *
         * 链接预览可能异步补齐，因此同一条剪贴内容可能先以无预览状态保存，再带着解析结果保存一次。
         */
        suspend fun save(link: String?, linkMeta: LinkMeta?): ClipProcessResult {
            /** 入库使用的捕获时间；Shizuku Provider 会传入回调入口时间，前台读取则使用当前时间。 */
            val clipTimestamp = capturedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
            /** 链接预览是否存在，用于脱敏日志，不输出具体 URL 或标题。 */
            val hasLinkMeta = linkMeta != null
            /** 本次传给 Repository 的剪贴捕获实体，包含内容、来源和链接预览摘要。 */
            val captureEntity = ClipCaptureEntity(
                content = clipContent,
                timestamp = clipTimestamp,
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

            logI(TAG) {
                "processClipText: 准备入库 textLength=${clipContent.length} timestamp=$clipTimestamp " +
                    "packageName=${packageName} hasLink=${!link.isNullOrBlank()} hasLinkMeta=$hasLinkMeta"
            }

            /** 保存动作结果；只有真实写库时才发送通知和调度自动备份。 */
            val saveResult = clipRepository.get().addNewClip(captureEntity)
            return when (saveResult) {
                is ClipSaveResult.Saved -> {
                    BackupAutoScheduler.markDirtyAndSchedule(appContext)
                    logD(TAG) { "processClip: 保存到数据库 clipId=${saveResult.clipId}" }
                    notificationHelper.get().notifyClipUpdate(
                        title = "$appName ${appContext.getString(R.string.base_general_it_was_written_into_the_clipboard)}",
                        content = "${appContext.getString(R.string.base_general_content)}${clipContent}",
                        clipId = saveResult.clipId
                    )
                    ClipProcessResult.Saved
                }
                is ClipSaveResult.SkippedDuplicate -> {
                    logD(TAG) {
                        "processClipText: Repository 判定重复跳过 textLength=${clipContent.length} " +
                            "packageName=${packageName} existingClipId=${saveResult.clipId}"
                    }
                    ClipProcessResult.DuplicateOrEmpty
                }
            }
        }

        /** 本次剪贴内容提取出的首个 URL；日志只记录长度，不输出完整链接。 */
        val extractedLink = LinkUtils.extractFirstUrl(clipContent)
        if (extractedLink.isNullOrBlank()) {
            return@withContext save(extractedLink, null)
        }

        if (LinkUtils.isImageUrl(extractedLink)) {
            return@withContext save(
                extractedLink,
                LinkMeta(
                    title = null,
                    description = null,
                    imageUrl = extractedLink,
                    siteName = null,
                )
            )
        }

        if (LinkUtils.isDownloadableMediaUrl(clipContent)) {
            // 纯媒体直链通常拿不到 OpenGraph 预览图，跳过网络解析可以让记录更快出现在列表里。
            return@withContext save(extractedLink, null)
        }

        /** 历史链接预览数据；命中后复用已解析结果，避免重复网络请求。 */
        val history = clipRepository.get().loadLinkPreview(extractedLink)
        if (!history?.imageUrl.isNullOrBlank()) {
            logD(TAG) { "processClipText 使用数据库中的链接数据 linkLength=${extractedLink.length}" }
            /** 数据库中已有的链接预览，避免重复解析链接。 */
            val linkMeta = LinkMeta(history.title, history.description, history.imageUrl, history.siteName)
            return@withContext save(extractedLink, linkMeta)
        }

        // 解析链接在网络比较差的情况下，耗时长，所以先保存一次剪贴数据，等到链接解析完成之后再更新一次剪贴数据，
        // 这样用户就能第一时间看到保存的剪贴数据了，而不是等链接解析完成之后才看到保存的剪贴数据
        /** 基础保存结果；如果 Repository 判定重复跳过，则不再继续解析链接预览。 */
        val initialSaveResult = save(extractedLink, null)
        if (initialSaveResult != ClipProcessResult.Saved) {
            return@withContext initialSaveResult
        }

        logD(TAG) { "processClipText 去解析链接 linkLength=${extractedLink.length}" }
        // 解析链接可能会比较慢，所以放在协程里，解析完成之后再保存数据
        // 避免网络比较差的情况下，需要很长时间才能看到保存的剪贴数据
        val deferred = async {
            val linkMeta = linkMetaParser.parse(extractedLink)
            logD(TAG) { "processClipText 链接解析完成 hasImage=${!linkMeta.imageUrl.isNullOrBlank()}" }
            linkMeta
        }

        save(extractedLink, deferred.await())
    }

    /**
     * 保存剪贴板中的图片并返回应用内可访问 URI。
     *
     * 当前图片剪贴处理尚未启用，这个方法保留给后续 OCR 或图片剪贴入库；失败时返回空字符串表示不保存该图片。
     */
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

/**
 * 剪贴内容处理结果。
 *
 * Provider commit_clip 用它区分真实入库和沿用既有去重/空内容规则跳过保存的情况。
 */
enum class ClipProcessResult {
    /** 本次剪贴内容已经保存或更新到数据库。 */
    Saved,

    /** 本次剪贴内容为空或命中连续重复规则，没有新增记录。 */
    DuplicateOrEmpty,
}
