package com.cla.clip.base.general

import android.app.Application
import android.content.Context

open class BaseApplication : Application() {

    lateinit var context: Context

    override fun onCreate() {
        super.onCreate()
        context = this
    }
}