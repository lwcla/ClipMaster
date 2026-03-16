package com.cla.clip.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.cla.clip.base.general.logD
import com.cla.clip.base.general.logE
import com.cla.clip.base.general.logI
import com.cla.clip.base.general.utils.NotificationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShizukuManager @Inject constructor(
    @param:ApplicationContext private val applicationContext: Context,
    private val notificationManager: NotificationManager
) {

    companion object {

        private const val TAG = "ShizukuManager"

        private const val name = "shizuku"


    }

    private val packageName get() = BuildConfig.APPLICATION_ID

    private val userServiceArgs = Shizuku.UserServiceArgs(ComponentName(packageName, ClipboardShizukuService::class.java.name))
        .daemon(true) // 守护进程，确保服务在后台持续运行
        .processNameSuffix(name)
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val serviceConnected = AtomicBoolean(false)

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            // 连接成功之后，更新通知
            logD(TAG) { "onServiceConnected: " }
            serviceConnected.set(true)
            if (binder != null && binder.pingBinder()) {
                val service = IClipboardShizukuService.Stub.asInterface(binder)
                service.start()
            } else {
                updateNotification()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // 连接断开之后，更新通知
            logD(TAG) { "onServiceDisconnected: " }
            serviceConnected.set(false)
            updateNotification()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        logD(TAG) { "binderReceivedListener: " }
        updateNotification()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        logD(TAG) { "binderDeadListener: " }
        updateNotification()
    }

    fun bindService() {
        if (!serviceConnected.get()) {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            runCatching { Shizuku.unbindUserService(userServiceArgs, userServiceConnection, false) }.getOrElse {
                logE(TAG, it) { "unbindUserService 失败: ${it.message}" }
            }
        }

        if (ShizukuUtils.isConnected(applicationContext)) {
            logI(TAG) { "Shizuku 已连接，正在绑定服务" }

            if (!serviceConnected.get()) {
                Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
                Shizuku.bindUserService(userServiceArgs, userServiceConnection)
            }
        } else {
            logE(TAG) { "Shizuku 未连接，不绑定服务" }
            updateNotification()
        }
    }

    fun updateNotification() {
        if (!notificationManager.hasPermission) {
            return
        }

        val status = ShizukuUtils.checkStatus(applicationContext)
        logD(TAG) { "去更新通知栏 : Shizuku 状态：$status" }

        // 连接状态发生变化时，更新通知
        val contentText = when (status) {
            is ShizukuStatus.Connected -> applicationContext.getString(com.cla.clip.base.general.R.string.base_general_service_is_running)
            is ShizukuStatus.Disconnect.NotInstalled -> applicationContext.getString(com.cla.clip.base.general.R.string.base_general_shizuku_not_install)
            is ShizukuStatus.Disconnect.ServiceNotAlive -> applicationContext.getString(com.cla.clip.base.general.R.string.base_general_shizuku_service_not_alive)
            is ShizukuStatus.Disconnect.VersionTooLow -> applicationContext.getString(com.cla.clip.base.general.R.string.base_general_shizuku_version_too_low)
            is ShizukuStatus.Disconnect.NotGranted -> applicationContext.getString(com.cla.clip.base.general.R.string.base_general_shizuku_not_granted)
        }

        notificationManager.shizukuStatus(contentText)
    }

}