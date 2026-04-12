package com.cla.clip.base.general.utils

val String.showName
    get() = if (length > 10) {
        "${take(10)}..."
    } else {
        this
    }
