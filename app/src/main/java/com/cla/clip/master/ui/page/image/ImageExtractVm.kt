package com.cla.clip.master.ui.page.image

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.base.general.entity.ImageCandidateData
import com.cla.clip.base.general.entity.ImageExtractRepository
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.master.work.DownloadImagesWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ImageProbeState {
    data object Idle : ImageProbeState
    data class Probing(val sessionId: Int) : ImageProbeState
    data class Extracted(val batchId: Long, val count: Int) : ImageProbeState
    data object Failed : ImageProbeState
}

@HiltViewModel
class ImageExtractVm @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val imageExtractRepo: ImageExtractRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "ImageExtractVm"
    }

    var probeState by mutableStateOf<ImageProbeState>(ImageProbeState.Idle)

    var sessionId by mutableIntStateOf(0)

    private val networkCandidates = linkedMapOf<String, ImageCandidateData>()

    /** 网络拦截只作为 DOM 顺序之外的补充，避免请求顺序影响最终命名顺序。 */
    fun addNetworkCandidate(candidate: ImageCandidateData) {
        if (candidate.url.isBlank() || networkCandidates.containsKey(candidate.url)) {
            return
        }
        networkCandidates[candidate.url] = candidate.copy(displayOrder = Int.MAX_VALUE - networkCandidates.size)
    }

    /** 保存提取结果到数据库，页面退出后 Worker 仍能读取完整候选列表。 */
    fun saveExtractedImages(pageUrl: String, pageName: String, domCandidates: List<ImageCandidateData>) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val merged = mergeCandidates(domCandidates)
                if (merged.isEmpty()) {
                    probeState = ImageProbeState.Failed
                    return@launch
                }
                val batchId = imageExtractRepo.createBatch(pageUrl, pageName, merged)
                logD(TAG) { "saveExtractedImages: batchId=$batchId count=${merged.size}" }
                probeState = ImageProbeState.Extracted(batchId, merged.size)
            }.onFailure {
                logE(TAG, it) { "saveExtractedImages: 保存图片候选失败" }
                probeState = ImageProbeState.Failed
            }
        }
    }

    /** 用户确认后才启动 Worker，避免提取到图片就立即占用网络和存储。 */
    fun startDownload(batchId: Long) {
        DownloadImagesWorker.enqueue(appContext, batchId)
    }

    /** 观察批量任务状态，下载完成后展示成功/失败数量。 */
    fun observeBatch(batchId: Long): Flow<ImageExtractBatchData?> {
        return imageExtractRepo.observeBatch(batchId)
    }

    private fun mergeCandidates(domCandidates: List<ImageCandidateData>): List<ImageCandidateData> {
        val result = linkedMapOf<String, ImageCandidateData>()
        domCandidates.forEachIndexed { index, candidate ->
            val url = candidate.url.trim()
            if (url.isNotBlank() && !result.containsKey(url)) {
                result[url] = candidate.copy(displayOrder = index)
            }
        }
        networkCandidates.values.forEach { candidate ->
            val url = candidate.url.trim()
            if (url.isNotBlank() && !result.containsKey(url)) {
                // 网络补充图排在 DOM 图片后面，确保用户看到的页面顺序优先。
                result[url] = candidate.copy(displayOrder = result.size)
            }
        }
        return result.values.toList()
    }
}
