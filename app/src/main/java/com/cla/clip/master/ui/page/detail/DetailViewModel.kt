package com.cla.clip.master.ui.page.detail

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.R
import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.base.general.config.ClipSourceBlockRules
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.toast
import com.cla.clip.master.processor.ClipboardDataProcessor
import com.cla.clip.master.processor.DefaultClipboardDataProcessor
import com.cla.clip.master.work.BackupAutoScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import javax.inject.Inject

/**
 * 剪贴详情页 UI 状态。
 *
 * 详情页只围绕单条剪贴记录渲染，因此状态保持为加载、成功、失败三态；
 * 失败信息使用字符串而不是异常对象，避免 UI 层依赖数据库或网络异常类型。
 */
sealed class DetailUiState {
    /** 正在根据路由中的剪贴 ID 查询数据库。 */
    data object Loading : DetailUiState()

    /** 成功加载到剪贴详情，页面可展示正文和操作按钮。 */
    data class Success(val clip: ClipShowEntity) : DetailUiState()

    /** 剪贴记录不存在或加载失败时展示的用户可见错误。 */
    data class Error(val message: String) : DetailUiState()
}

/**
 * 剪贴详情页 ViewModel。
 *
 * 负责根据路由 ID 加载单条剪贴记录，并复用 `ClipboardDataProcessor` 完成删除、复制等操作。
 * 类内保留的抖音视频解析方法属于早期实验性逻辑，当前正式视频提取已迁移到视频提取页的 WebView 探测流程。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class DetailViewModel @Inject constructor(
    /** 应用级 Context，用于在 IO 查询中安全读取字符串资源。 */
    @param:ApplicationContext private val appContext: Context,

    /** 剪贴仓库使用 Lazy，避免详情页创建时立即初始化数据库查询依赖。 */
    private val clipRepository: dagger.Lazy<ClipRepository>,

    /** 通用剪贴操作处理器，详情页通过委托保持复制、删除和列表页行为一致。 */
    private val clipboardDataProcessor: DefaultClipboardDataProcessor
) : ViewModel(), ClipboardDataProcessor by clipboardDataProcessor {

    companion object {
        private const val TAG = "DetailViewModel"
    }

    /**
     * 当前详情页需要加载的剪贴 ID。
     *
     * 使用 Flow 承接路由变化，确保同一个 DetailPage 实例切换 ID 时会取消旧查询并加载新记录。
     */
    private val _clipIdFlow = MutableStateFlow<Long?>(null)

    /**
     * 详情页状态流。
     *
     * `transformLatest` 保证路由 ID 快速变化时只保留最后一次查询结果，避免旧记录覆盖新详情。
     */
    val clipFlow = _clipIdFlow.filterNotNull().transformLatest { id ->
        val clip = clipRepository.get().loadClipDetail(id)
        currentCoroutineContext().ensureActive()

        if (clip == null) {
            emit(DetailUiState.Error(appContext.getString(R.string.base_general_clip_not_found)))
            return@transformLatest
        }

        emit(DetailUiState.Success(clip))
    }.stateIn(
        CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO),
        SharingStarted.Lazily,
        DetailUiState.Loading
    )

    /** 详情页订阅的来源过滤名单；从“我的”页或备份恢复改变后，详情动作会自动刷新。 */
    val blockedClipSourcePackages = AppSetting.blockedClipSourcePackagesFlow

    /**
     * 提交需要展示的剪贴 ID。
     *
     * 页面在 `LaunchedEffect(clipId)` 中调用，避免 Compose 每次重组都触发数据库读取。
     */
    fun loadClip(id: Long) {
        _clipIdFlow.update { id }
    }

    /**
     * 从详情页屏蔽当前来源 App 的后续剪贴。
     *
     * 只影响未来保存，不删除当前记录或历史记录；来源未知时直接忽略。
     */
    fun blockSourceAppFromDetail(packageName: String) {
        /** 规范化后的来源包名；为空或非法时不写入过滤名单。 */
        val normalizedPackageName = ClipSourceBlockRules.normalizeSinglePackage(packageName) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (AppSetting.addBlockedPackage(normalizedPackageName)) {
                logI(TAG) { "详情页加入来源过滤名单 reasonCode=detail_block_source_added" }
                BackupAutoScheduler.markDirtyAndSchedule(appContext)
            }
            appContext.toast(R.string.base_general_clip_source_block_detail_added)
        }
    }

    /**
     * 从详情页取消屏蔽当前来源 App 的后续剪贴。
     *
     * 只移除过滤规则，当前详情记录保留在页面上。
     */
    fun unblockSourceAppFromDetail(packageName: String) {
        /** 规范化后的来源包名；为空或非法时不写入过滤名单。 */
        val normalizedPackageName = ClipSourceBlockRules.normalizeSinglePackage(packageName) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (AppSetting.removeBlockedPackage(normalizedPackageName)) {
                logI(TAG) { "详情页移除来源过滤名单 reasonCode=detail_block_source_removed" }
                BackupAutoScheduler.markDirtyAndSchedule(appContext)
            }
            appContext.toast(R.string.base_general_clip_source_block_detail_removed)
        }
    }

    /** 详情页早期视频解析实验共用的 OkHttpClient，懒加载以避免无网络操作时创建连接池。 */
    private val client by lazy { OkHttpClient() }

    /**
     * 触发早期抖音页面解析实验。
     *
     * 当前没有对外暴露解析结果，保留入口仅用于后续迁移或调试；正式下载链路应走 VideoExtractVm。
     */
    fun download(pageUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            extractDouyinVideoUrl(pageUrl)
        }
    }

    /**
     * 处理抖音短链重定向并提取视频 URL。
     *
     * 这是基于 HTML 和 Meta 的启发式解析，平台字段变动时可能失效；
     * 因此失败时只记录日志并返回 null，不影响详情页主流程。
     */
    private suspend fun extractDouyinVideoUrl(pageUrl: String): String? {
        return try {
            // 1. 跟随重定向获取真实 URL
            val realUrl = followRedirect(pageUrl)
            Log.d("DouyinExtract", "重定向后的 URL: $realUrl")
            logD(TAG) {
                """
                重定向
                原来的url=${pageUrl}
                重定向后的url=${realUrl}
            """.trimIndent()
            }

            // 2. 下载 HTML 内容
            val html = downloadHtml(realUrl, pageUrl)
            logD(TAG) { html }

            // 3. 从 HTML 中提取视频 URL（可能在 JS 变量中）
            var videoUrl = extractVideoUrlFromJsVariable(html)
            logD(TAG) { "从 HTML 中提取视频 URL videoUrl=$videoUrl" }
            if (!videoUrl.isNullOrBlank()) return videoUrl

            // 4. 尝试从响应头或 meta 标签中查找
            videoUrl = extractVideoUrlFromMeta(html)
            logD(TAG) { "尝试从响应头或 meta 标签中查找 videoUrl=$videoUrl" }
            if (!videoUrl.isNullOrBlank()) return videoUrl

            null
        } catch (e: Exception) {
            logE(TAG, e) { "提取失败" }
            null
        }
    }

    /**
     * 下载网页 HTML。
     *
     * referer 仅在短链跳转场景下补充，用于提升部分站点返回完整页面的概率；调用方负责在 IO 线程执行。
     */
    private suspend fun downloadHtml(url: String, referer: String = ""): String {
        val builder = Request.Builder().url(url)
        if (referer.isNotBlank()) {
            builder.header("Referer", referer)
        }
        val request = builder.build()
        val response = client.newCall(request).execute()
        return response.body?.string() ?: ""
    }


    /**
     * 跟随 HTTP 重定向并返回最终地址。
     *
     * 使用桌面浏览器 UA 是为了减少移动短链返回中间页的概率；这属于解析兜底策略，不参与正式下载任务创建。
     */
    private suspend fun followRedirect(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val response = client.newCall(request).execute()

        // 返回最终的 URL（自动跟随重定向）
        return response.request.url.toString()
    }

    /**
     * 从 JavaScript 全局变量中提取视频 URL。
     *
     * 例如 `window.__UNIVERSAL_DATA_FOR_REHYDRATION__` 中可能携带播放地址；正则只匹配常见字段，
     * 命中后不做进一步校验，调用方需要接受误判或平台字段变更导致的空结果。
     */
    private fun extractVideoUrlFromJsVariable(html: String): String? {
        return try {
            // 优先读取播放地址，通常比下载地址更接近用户实际看到的视频资源。
            val regex1 = Regex(""""playAddr"\s*:\s*"([^"]+)""")
            var match = regex1.find(html)
            if (match != null) return match.groupValues[1]

            // downloadAddr 在部分页面里存在水印或权限限制，因此放在播放地址之后。
            val regex2 = Regex(""""downloadAddr"\s*:\s*"([^"]+)""")
            match = regex2.find(html)
            if (match != null) return match.groupValues[1]

            // videoUrl 是更泛化的兜底字段，误判概率更高，只在前两个字段缺失时使用。
            val regex3 = Regex(""""videoUrl"\s*:\s*"([^"]+)""")
            match = regex3.find(html)
            if (match != null) return match.groupValues[1]

            null
        } catch (e: Exception) {
            logE(TAG, e) { "JS 变量提取失败" }
            null
        }
    }

    /**
     * 从 Meta 标签中提取视频 URL。
     *
     * OpenGraph 标签通常给分享卡片使用，可能不是最高质量视频源；这里只作为 JS 变量解析失败后的兜底。
     */
    private fun extractVideoUrlFromMeta(html: String): String? {
        return try {
            val doc = Jsoup.parse(html)

            // 查找 og:video 标签
            var meta = doc.selectFirst("meta[property=og:video]")
            if (meta != null) {
                val url = meta.attr("content")
                if (url.isNotBlank()) return url
            }

            // 查找其他视频相关的 meta 标签
            meta = doc.selectFirst("meta[name=video_url]")
            if (meta != null) {
                val url = meta.attr("content")
                if (url.isNotBlank()) return url
            }

            null
        } catch (e: Exception) {
            logE(TAG, e) { "Meta 标签提取失败" }
            null
        }
    }
}
