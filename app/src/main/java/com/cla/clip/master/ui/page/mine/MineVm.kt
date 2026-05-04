package com.cla.clip.master.ui.page.mine

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.cla.clip.base.general.utils.hasNotificationPermission
import com.cla.clip.base.general.utils.hasOverlayPermission
import com.cla.clip.base.general.utils.logD
import com.cla.clip.master.entity.SettingSwitchItemUi
import com.cla.clip.shizuku.ShizukuUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

@HiltViewModel
class MineVm @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
) : ViewModel() {

    companion object {
        private const val TAG = "MineVm"
    }

    var permissionExpanded by mutableStateOf(false)
        private set

    var shizukuChecked by mutableStateOf(false)
        private set
    var notificationChecked by mutableStateOf(false)
        private set
    var overlayChecked by mutableStateOf(false)
        private set

    fun togglePermissionExpanded() {
        permissionExpanded = !permissionExpanded
    }

    fun refreshPermissionStatus() {
        shizukuChecked = ShizukuUtils.isConnected(appContext)
        notificationChecked = appContext.hasNotificationPermission()
        overlayChecked = appContext.hasOverlayPermission()
        logD(TAG) { "refreshPermissionStatus shizukuChecked=$shizukuChecked notificationChecked=$notificationChecked overlayChecked=$overlayChecked" }
    }

    fun onItemCheckedChange(id: SettingSwitchItemUi.Id, checked: Boolean) {
        when (id) {
            SettingSwitchItemUi.Id.Permission.Shizuku -> shizukuChecked = checked
            SettingSwitchItemUi.Id.Permission.Notice -> notificationChecked = checked
            SettingSwitchItemUi.Id.Permission.Overlay -> overlayChecked = checked
        }
    }
}