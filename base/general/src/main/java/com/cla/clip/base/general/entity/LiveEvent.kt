package com.cla.clip.base.general.entity

data class LiveEvent<T>(private val data: T) {

    val peekContent
        get() = data

    @Volatile
    var hasBeenHandled = false
        private set

    val content: T?
        get() = if (hasBeenHandled) {
            null
        } else {
            hasBeenHandled = true
            data
        }
}