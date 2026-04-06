package com.cla.clip.master.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.cla.clip.base.general.utils.ApplicationScope
import com.cla.clip.base.general.utils.logI
import com.cla.clip.master.utils.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val progress: Int) : DownloadState()
    data class Success(val filePath: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/** 下载视频的服务 */
@AndroidEntryPoint
class DownloadVideoService : Service() {

    companion object {
        private const val TAG = "DownloadVideoService"
    }

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    @Inject
    @ApplicationContext
    lateinit var appContext: Context

    @Inject
    lateinit var notificationHelper: NotificationHelper

    override fun onCreate() {
        super.onCreate()
        logI(TAG) { "onCreate: " }
        startForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground()
        return super.onStartCommand(intent, flags, startId)
    }


    private fun startForeground() {
        notificationHelper.startForeground(this, "", "")
    }


    override fun onBind(intent: Intent?): IBinder? = null
}