package com.cla.clip.master.onboarding

import android.content.pm.PackageManager
import com.cla.clip.shizuku.ShizukuUtils
import rikka.shizuku.Shizuku

/**
 * 首次会话的 SDK 结果订阅，与 ViewModel 同生共灭，不依赖 Compose 宿主是否存在。
 * SDK 不向晚注册监听重放结果，因此旋转期间必须持续注册；这里不持有 Activity 或导航对象。
 */
internal class FirstLaunchShizukuSubscription(
    /** 同一 ViewModel 保存的动作序列。 */ private val permissions: FirstLaunchPermissionSequence,
    /** 可替换的 SDK 注册边界，测试可模拟瞬时结果而不初始化 Binder。 */ register: (Shizuku.OnRequestPermissionResultListener) -> Unit,
    /** ViewModel 真正清理时注销，不能放到 Composable onDispose。 */ private val unregister: (Shizuku.OnRequestPermissionResultListener) -> Unit,
    /** 成功授权后仅连接应用级服务，不捕获 Activity。 */ private val onGranted: () -> Unit,
) : AutoCloseable {
    /** 只接收本轮正在等待的首次授权，重复结果和后续手动授权不重复推进。 */
    private val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == ShizukuUtils.REQUEST_CODE && permissions.step.value == FirstLaunchPermissionStep.WaitingShizuku) {
            permissions.shizukuFinished()
            if (grantResult == PackageManager.PERMISSION_GRANTED) onGranted()
        }
    }

    init { register(listener) }

    /** 宿主旋转不调用此方法，只有会话最终释放才移除 SDK 监听。 */
    override fun close() { unregister(listener) }
}
