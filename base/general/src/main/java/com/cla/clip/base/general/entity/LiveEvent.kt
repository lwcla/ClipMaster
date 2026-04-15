package com.cla.clip.base.general.entity

data class LiveEvent<T>(val data: T) {

    val peekContent
        get() = data

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