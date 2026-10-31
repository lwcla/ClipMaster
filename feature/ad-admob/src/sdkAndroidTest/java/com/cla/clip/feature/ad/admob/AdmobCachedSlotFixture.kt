package com.cla.clip.feature.ad.admob

import android.content.Context
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.runtime.MutableState
import com.cla.clip.feature.ad.api.AdPlacement
import com.cla.clip.feature.ad.api.AdPrivacyState
import com.cla.clip.feature.ad.api.AdReleaseReason
import com.cla.clip.feature.ad.api.AdSlotEvent
import com.cla.clip.feature.ad.api.AdSlotRequest
import com.cla.clip.feature.ad.api.AdSlotResourceOwner
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.Proxy

/** 只合成已完成加载的边界，回绑、呈现、解绑和销毁仍运行真实生产会话；所有操作在主线程执行。 */
internal class AdmobCachedSlotFixture(
    /** 独立测试宿主上下文，不访问应用用户数据。 */
    context: Context,
) {
    /** 同访问内始终复用的生产资源所有者。 */
    val owner = AdSlotResourceOwner()
    /** 收集真实会话事件，检查离屏不会引发释放。 */
    val events = mutableListOf<AdSlotEvent>()
    /** 合成素材的销毁次数，用于区分移除 View 和释放资源。 */
    var destroyCount = 0
        private set
    /** 生产隐私控制器仅预装允许状态，不调用 UMP 的刷新或表单接口。 */
    private val privacy = AdmobPrivacyController(context)
    /** 生产初始化器不启动网络；夹具预装已启动/已加载会话。 */
    private val initializer = AdmobInitializer(context)
    /** 被测入口与应用使用同一实现，没有复制 AndroidView 接线。 */
    val source = AdmobAdSourceEntry(privacy, initializer)
    /** 请求只含合成身份与既有广告位，所有默认许可允许展示。 */
    val request = AdSlotRequest(AdPlacement.BackupOverviewNative, "cached-test", true, resourceOwner = owner)
    /** 测试使用真实 SDK 容器并提供确定高度，不注册素材或产生曝光。 */
    val nativeView = NativeAdView(context).apply {
        addView(TextView(context).apply {
            text = "合成广告"
            minimumHeight = 160
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 160))
    }
    /** 真实会话承接合成加载结果，后续状态由生产方法更新。 */
    val session = AdmobSlotSession(context, request, initializer, privacy, "synthetic-unit", events::add)

    init {
        // 反射仅建立网络加载结束后的私有缓存前提；不调用私有回绑方法，也不为测试扩大生产 API。
        @Suppress("UNCHECKED_CAST")
        val consent = readField(privacy, "mutableState") as MutableStateFlow<AdPrivacyState>
        consent.value = AdPrivacyState(canRequestAds = true)
        // SDK 素材只承担缓存对象和销毁计数，不代替被测 View 或会话。
        val ad = Proxy.newProxyInstance(NativeAd::class.java.classLoader, arrayOf(NativeAd::class.java)) { proxy, method, args ->
            when (method.name) {
                "destroy" -> { destroyCount++; null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "SyntheticCachedAd"
                else -> null
            }
        } as NativeAd
        writeField(session, "ad", ad)
        writeField(session, "started", true)
        // 与生产加载成功时相同，缓存 View 与呈现标记在同次主线程提交中可用。
        @Suppress("UNCHECKED_CAST")
        val viewState = readField(session, "view\$delegate") as MutableState<NativeAdView?>
        // 初始呈现标记模拟已展示素材，之后交给生产解绑和回绑逻辑更新。
        @Suppress("UNCHECKED_CAST")
        val presentedState = readField(session, "presented\$delegate") as MutableState<Boolean>
        viewState.value = nativeView
        presentedState.value = true
        owner.getOrCreate { session }
    }

    /** 测试结束沿用页面所有者的幂等释放，防止独立测试 Activity 泄漏。 */
    fun close() { owner.close(AdReleaseReason.PageLeft) }

    /** 仅测试准备读取指定字段，字段改名应显式更新夹具而非静默跳过。 */
    private fun readField(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).run {
        isAccessible = true
        get(target)
    }

    /** 仅测试准备写入合成加载结果，不推进被测绑定或呈现流程。 */
    private fun writeField(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
