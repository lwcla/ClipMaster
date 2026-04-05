package com.cla.clip.master.ui.navigation

import kotlinx.serialization.Serializable

sealed class Route

/** 主页 */
@Serializable
data object MainRoute : Route()

/** 详情页 */
@Serializable
data class DetailRoute(val clipId: Long) : Route()
