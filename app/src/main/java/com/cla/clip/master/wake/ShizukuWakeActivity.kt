package com.cla.clip.master.wake

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.annotation.Keep
import com.cla.clip.base.general.utils.logD
import com.cla.clip.master.utils.ShizukuConnector
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Shizuku 唤醒 app 主进程的 NoDisplay Activity。
 *
 * 该入口只负责被 Shizuku 通过 `am start --activity-no-animation` 显式拉起主进程；
 * 不展示业务 UI，不读取剪贴板正文，也不保存数据，只提交 Shizuku 重连请求后立即关闭自己。
 */
@AndroidEntryPoint
@Keep
class ShizukuWakeActivity : ComponentActivity() {

    companion object {
        /** NoDisplay 唤醒页日志标签，用于确认 shell `am start` 是否真的拉起 app 主进程。 */
        private const val TAG = "ShizukuWakeActivity"

        /** Shizuku 连接请求原因码，用于和 Provider 身份查询触发的连接区分。 */
        private const val WAKE_REASON_CODE = "wake_activity"
    }

    /** Shizuku 连接管理器；唤醒页启动后显式提交一次长生命周期连接请求，避免依赖 Activity 存活等待 callback。 */
    @Inject
    lateinit var shizukuConnector: ShizukuConnector

    /**
     * 创建 NoDisplay 唤醒页并提交 Shizuku 连接请求。
     *
     * @param savedInstanceState 系统恢复状态；唤醒页不保存 UI 状态，只按冷启动处理并立即结束。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            requestShizukuReconnect(entryReason = "on_create")
        } finally {
            finish()
        }
    }

    /**
     * 复用 NoDisplay 唤醒页时重新提交连接请求。
     *
     * @param intent 新的显式启动 Intent；只用于更新 Activity 当前 intent，不读取业务参数。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        try {
            requestShizukuReconnect(entryReason = "on_new_intent")
        } finally {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        logD(TAG) { "onDestroy: " }
    }

    /**
     * 请求主进程重新连接 Shizuku UserService。
     *
     * @param entryReason Activity 生命周期入口原因，用于低敏日志区分冷启动和复用。
     */
    private fun requestShizukuReconnect(entryReason: String) {
        /** Shizuku 连接请求结果；只记录是否提交请求和期望进程名，不包含剪贴板内容。 */
        val connectResult = shizukuConnector.requestConnect(WAKE_REASON_CODE)
        logD(TAG) {
            "NoDisplay 唤醒页请求 Shizuku 重连 entryReason=$entryReason " +
                "requested=${connectResult.requested} expectedProcessName=${connectResult.expectedProcessName} " +
                "reasonCode=${connectResult.reasonCode}"
        }
    }
}
