package com.cla.clip.feature.ad.admob

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cla.clip.feature.ad.api.AdPrivacyController
import com.cla.clip.feature.ad.api.AdPrivacyState
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** UMP 是唯一隐私事实来源；不把 canRequestAds 等同于个性化广告授权。 */
@Singleton
internal class AdmobPrivacyController @Inject constructor(
    /** 仅保留应用 Context，不保留 Activity。 */
    @ApplicationContext private val context: Context,
) : AdPrivacyController {
    /** SDK 在 refresh 前不访问，避免 Hilt 构造触发隐私网络工作。 */
    private val information by lazy { UserMessagingPlatform.getConsentInformation(context) }
    /** 所有状态变更统一派发到主线程。 */
    private val main = Handler(Looper.getMainLooper())
    /** 默认关闭，不能用应用自己的持久化标志代替 UMP。 */
    private val mutableState = MutableStateFlow(AdPrivacyState())
    /** 宿主只观察轻量隐私快照。 */
    override val state = mutableState.asStateFlow()
    /** 防止重组或 Activity 重建同时发起多个表单。 */
    private var refreshing = false
    /** 每个进程启动刷新一次，失败允许后续显式刷新。 */
    private var refreshed = false

    /** 启动时请求最新隐私状态，表单异常只记录低敏错误编号。 */
    override fun refresh(activity: Activity) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { refresh(activity) }
            return
        }
        if (refreshing || refreshed || activity.isFinishing || activity.isDestroyed) return
        refreshing = true
        // 刷新和表单展示期间暂停广告，避免在隐私遮罩背后加载或曝光。
        mutableState.value = state.value.copy(canRequestAds = false)
        information.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
            if (activity.isFinishing || activity.isDestroyed) {
                refreshing = false
                publish()
            } else {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                    refreshing = false
                    refreshed = error == null
                    if (error != null) Log.w("AdmobPrivacy", "隐私表单失败 code=${error.errorCode}")
                    publish()
                }
            }
        }, { error ->
            refreshing = false
            Log.w("AdmobPrivacy", "隐私刷新失败 code=${error.errorCode}")
            publish()
        })
        // 等待必需表单关闭或错误回调后再读取 canRequestAds，避免并行展示。
    }

    /** 隐私选项打开期间暂停广告；关闭后以平台最新状态恢复。 */
    override fun showPrivacyOptions(activity: Activity) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { showPrivacyOptions(activity) }
            return
        }
        if (refreshing || activity.isFinishing || activity.isDestroyed || !state.value.privacyOptionsRequired) return
        refreshing = true
        mutableState.value = state.value.copy(canRequestAds = false)
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            refreshing = false
            if (error != null) Log.w("AdmobPrivacy", "隐私选项失败 code=${error.errorCode}")
            publish()
        }
    }

    /** 读取 UMP 当前结果并原子发布，不输出同意字符串或设备标识。 */
    private fun publish() {
        mutableState.value = AdPrivacyState(
            canRequestAds = information.canRequestAds(),
            privacyOptionsRequired = information.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED,
        )
    }
}
