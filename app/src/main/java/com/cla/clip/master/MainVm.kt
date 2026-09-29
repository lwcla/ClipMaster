package com.cla.clip.master

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.config.ad.AdSettingsRepository
import com.cla.clip.base.general.config.ad.AdSettingsSnapshot
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logE
import com.cla.clip.master.entity.ExtraData
import com.cla.clip.master.entity.ImageFolderOpenData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
/**
 * 主 Activity 的一次性导航状态 ViewModel。
 *
 * 通知点击可能带来剪贴板记录 id、视频下载任务 id 或图片保存目录；这些目标需要在 Compose 中被消费一次，
 * 否则重组、横竖屏或重复 intent 可能导致重复导航。
 */
class MainVm @Inject constructor(
    /** 广告设置由宿主单例 DataStore 提供，未就绪时 SDK 保持禁用。 */
    adSettingsRepository: AdSettingsRepository,
) : ViewModel() {

    /** Activity 可见时订阅的广告设置；null 表示尚未读到已提交配置。 */
    val adSettings: StateFlow<AdSettingsSnapshot?> = adSettingsRepository.data
        .map { settings -> settings as AdSettingsSnapshot? }
        .catch { error ->
            if (error is CancellationException) throw error
            // 读失败时撤销广告资格，不保留此前可能已同意的旧内存值。
            logE(TAG, error) { "读取广告设置失败 reasonCode=ad_settings_read_failed" }
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 未就绪时使用自动来源标识，但总开关保持关闭。 */
    val activeAdSourceIdFlow = adSettings.map { settings -> settings?.activeSourceId ?: AdSettingsRepository.DEFAULT_ACTIVE_SOURCE_ID }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AdSettingsRepository.DEFAULT_ACTIVE_SOURCE_ID)

    /** 未就绪或读失败时广告总开关为 false，禁止 SDK 提前启动。 */
    val adsGlobalEnabledFlow = adSettings.map { settings -> settings?.adsGlobalEnabled ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 未就绪时的同意状态为 unknown，不能提前授予来源资格。 */
    val adConsentStateFlow = adSettings.map { settings -> settings?.consentState ?: AdSettingsRepository.DEFAULT_CONSENT_STATE }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AdSettingsRepository.DEFAULT_CONSENT_STATE)

    /** 未就绪时不具备政策版本；同意状态不能跨版本误用。 */
    val adPrivacyPolicyVersionFlow = adSettings.map { settings -> settings?.privacyPolicyVersion.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    companion object {
        /** ViewModel 日志标签，用于排查通知目标是否被重复消费。 */
        private const val TAG = "MainVm"
    }

    /** 待打开的剪贴板记录 id，来自通知 intent；消费后会记录到 usedClipId，避免重复进入详情页。 */
    var pendingClipId by mutableStateOf<ExtraData?>(null)

    /** 最近一次已消费的剪贴板记录 id，带时间戳区分同一 id 的不同通知事件。 */
    private var usedClipId: ExtraData? = null

    /** 待打开的视频下载任务 id，来自下载结果通知；消费后会记录到 usedTaskId。 */
    var pendingTaskId by mutableStateOf<ExtraData?>(null)

    /** 最近一次已消费的下载任务 id，避免同一通知目标在重组时重复打开。 */
    private var usedTaskId: ExtraData? = null

    /** 待打开的图片保存目录，来自图片下载结果通知；消费后会记录到 usedImageFolderOpenData。 */
    var pendingImageFolderOpenData by mutableStateOf<ImageFolderOpenData?>(null)

    /** 最近一次已消费的图片目录打开数据，带时间戳区分同一目录的不同通知点击。 */
    private var usedImageFolderOpenData: ImageFolderOpenData? = null

    /**
     * 取出一个尚未消费的剪贴板记录 id。
     *
     * 返回 null 表示没有新目标或目标已被消费；调用方通常在 LaunchedEffect 中调用，拿到 id 后立即导航详情页。
     */
    fun pendingClipId(): Long? {
        if (usedClipId != pendingClipId) {
            usedClipId = pendingClipId
            return pendingClipId?.id
        }

        if (usedClipId != null && usedClipId == pendingClipId) {
            logI(TAG) { "pendingClipId: $pendingClipId 已经被使用过 usedClipId=$usedClipId" }
        }
        return null
    }

    /**
     * 取出一个尚未消费的视频下载任务 id。
     *
     * 返回 null 表示没有新下载目标或目标已被消费；调用方拿到 id 后进入下载结果页。
     */
    fun pendingTaskId(): Long? {
        if (usedTaskId != pendingTaskId) {
            usedTaskId = pendingTaskId
            return pendingTaskId?.id
        }

        if (usedTaskId != null && usedTaskId == pendingTaskId) {
            logI(TAG) { "pendingTaskId: $pendingTaskId 已经被使用过 usedTaskId=$usedTaskId" }
        }
        return null
    }

    /**
     * 取出一个尚未消费的图片保存目录。
     *
     * 返回 null 表示没有新目录目标或目标已被消费；调用方拿到目录后会尝试直接打开本批次图片文件夹。
     */
    fun pendingImageFolderOpenData(): ImageFolderOpenData? {
        if (usedImageFolderOpenData != pendingImageFolderOpenData) {
            usedImageFolderOpenData = pendingImageFolderOpenData
            return pendingImageFolderOpenData
        }

        if (usedImageFolderOpenData != null && usedImageFolderOpenData == pendingImageFolderOpenData) {
            logI(TAG) {
                "pendingImageFolderOpenData: $pendingImageFolderOpenData 已经被使用过 usedImageFolderOpenData=$usedImageFolderOpenData"
            }
        }
        return null
    }
}
