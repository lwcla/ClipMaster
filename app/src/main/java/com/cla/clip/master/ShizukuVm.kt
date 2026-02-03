package com.cla.clip.master

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import rikka.shizuku.Shizuku

class ShizukuVm : ViewModel() {

    private val _shizukuAvailable = mutableStateOf(false)

    val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
//            binding.permissionStatusTextView.text = "授权状态：用户同意了 Shizuku 授权请求"
            setShizukuAvailable(true)
        } else {
//            binding.permissionStatusTextView.text = "授权状态：用户拒绝了 Shizuku 授权请求"
            setShizukuAvailable(false)
        }
    }

    val shizukuAvailable: State<Boolean> = _shizukuAvailable

    fun setShizukuAvailable(available: Boolean) {
        _shizukuAvailable.value = available
    }

    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            // 尝试获取 Shizuku 的包信息
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}