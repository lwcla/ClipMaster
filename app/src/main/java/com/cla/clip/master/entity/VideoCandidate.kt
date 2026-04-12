package com.cla.clip.master.entity

import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import androidx.navigation.NavType
import com.cla.clip.base.general.dao.DownloadTaskData
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_DOWNLOADING
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_FAILED
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_SUCCESS
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
@Parcelize
data class VideoCandidate(
    val url: String,
    val referer: String?,
    val userAgent: String?,
    val cookie: String?,
    val fileName: String
) : Parcelable

object VideoCandidateNavType : NavType<VideoCandidate>(isNullableAllowed = false) {

    private val json = Json {
        ignoreUnknownKeys = true // 反序列化时忽略未知字段，增加兼容性
        encodeDefaults = true // 序列化时包含默认值字段，确保完整性
    }

    override fun put(bundle: Bundle, key: String, value: VideoCandidate) {
        bundle.putString(key, json.encodeToString(value))
    }

    override fun get(bundle: Bundle, key: String): VideoCandidate? {
        return bundle.getString(key)?.let { json.decodeFromString<VideoCandidate>(it) }
    }

    override fun parseValue(value: String): VideoCandidate {
        return json.decodeFromString(Uri.decode(value))
    }

    override fun serializeAsValue(value: VideoCandidate): String {
        return Uri.encode(json.encodeToString(value))
    }
}

sealed class VideoDownloadState {
    object Idle : VideoDownloadState()
    data class Downloading(val progress: Int) : VideoDownloadState()
    data class Success(val savePath: String?) : VideoDownloadState()
    data class Failed(val errorMsg: String?) : VideoDownloadState()
}

fun DownloadTaskData.toUi() = when (status) {
    STATUS_DOWNLOADING -> {
        VideoDownloadState.Downloading(progress.coerceIn(0, 100))
    }

    STATUS_SUCCESS -> {
        VideoDownloadState.Success(savePath)
    }

    STATUS_FAILED -> {
        VideoDownloadState.Failed(errorMsg)
    }

    else -> VideoDownloadState.Idle
}