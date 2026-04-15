package com.cla.clip.master.entity

import com.cla.clip.base.general.entity.LiveEvent

/** 读取剪贴板事件 */
data class ReadClipEvent(
    val resume: LiveEvent<Boolean>?,
    val hasFocus: LiveEvent<Boolean>?,
)