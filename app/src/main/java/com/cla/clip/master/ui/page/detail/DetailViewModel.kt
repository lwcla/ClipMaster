package com.cla.clip.master.ui.page.detail

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.R
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.master.processor.ClipboardDataProcessor
import com.cla.clip.master.processor.DefaultClipboardDataProcessor
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

sealed class DetailUiState {
    data object Loading : DetailUiState()
    data class Success(val clip: ClipShowEntity) : DetailUiState()
    data class Error(val message: String) : DetailUiState()
}

@HiltViewModel
class DetailViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val clipRepository: dagger.Lazy<ClipRepository>,
    private val clipboardDataProcessor: DefaultClipboardDataProcessor
) : ViewModel(), ClipboardDataProcessor by clipboardDataProcessor {

    companion object {
        private const val TAG = "DetailViewModel"
    }

    private val _clipIdFlow = MutableStateFlow<Long?>(null)
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

    fun loadClip(id: Long) {
        _clipIdFlow.update { id }
    }

    private val client by lazy { OkHttpClient() }

    fun download(pageUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            extractDouyinVideoUrl(pageUrl)
        }
    }

    /**
     * 处理抖音短链重定向并提取视频 URL
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
     * 下载 HTML 内容
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
     * 跟随 HTTP 重定向
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
     * 从 JavaScript 全局变量中提取视频 URL
     * 例如: window.__UNIVERSAL_DATA_FOR_REHYDRATION__ = {...}
     */
    private fun extractVideoUrlFromJsVariable(html: String): String? {
        return try {
            // 查找模式: "playAddr":"https://..."
            val regex1 = Regex(""""playAddr"\s*:\s*"([^"]+)""")
            var match = regex1.find(html)
            if (match != null) return match.groupValues[1]

            // 查找模式: "downloadAddr":"https://..."
            val regex2 = Regex(""""downloadAddr"\s*:\s*"([^"]+)""")
            match = regex2.find(html)
            if (match != null) return match.groupValues[1]

            // 查找模式: "videoUrl":"https://..."
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
     * 从 Meta 标签中提取视频 URL
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