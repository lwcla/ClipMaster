package com.cla.clip.master.entity

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class VideoCandidate(
    val url: String,
    val referer: String?,
    val userAgent: String?,
    val cookie: String?,
) : Parcelable