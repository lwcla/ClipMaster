# 穿山甲官方 SDK 的混淆规则随 SDK AAR 透出；本模块仅保留 SDK facade 名称，便于 R8 包定位公开 API。
-keep class com.cla.clip.feature.ad.csj.CsjSdkDirectClient { *; }

# 穿山甲 7.6.1.2 的 POM 没有声明以下可选/兼容包；R8 生成同等 dontwarn 后才能完成 release 验证。
# 后续升级 SDK 时必须重新跑 R8 并复核这些规则是否仍然必要。
-dontwarn android.app.Activity$TranslucentConversionListener
-dontwarn android.arch.lifecycle.Lifecycle
-dontwarn android.arch.lifecycle.LifecycleOwner
-dontwarn android.arch.lifecycle.LifecycleRegistry
-dontwarn android.arch.lifecycle.ViewModelStore
-dontwarn android.os.SystemProperties
-dontwarn com.bytedance.component.sdk.annotation.AnyThread
-dontwarn com.bytedance.component.sdk.annotation.CallSuper
-dontwarn com.bytedance.component.sdk.annotation.ColorInt
-dontwarn com.bytedance.component.sdk.annotation.DungeonFlag
-dontwarn com.bytedance.component.sdk.annotation.HungeonFlag
-dontwarn com.bytedance.component.sdk.annotation.IntRange
-dontwarn com.bytedance.component.sdk.annotation.Keep
-dontwarn com.bytedance.component.sdk.annotation.MainThread
-dontwarn com.bytedance.component.sdk.annotation.RawRes
-dontwarn com.bytedance.component.sdk.annotation.RequiresApi
-dontwarn com.bytedance.component.sdk.annotation.UiThread
-dontwarn com.bytedance.component.sdk.annotation.WorkerThread
-dontwarn com.bytedance.embed_dr.OaidVivoImpl$Type
-dontwarn com.bytedance.framwork.core.sdkmonitor.SDKMonitor$IGetExtendParams
-dontwarn com.bytedance.framwork.core.sdkmonitor.SDKMonitor
-dontwarn com.bytedance.framwork.core.sdkmonitor.SDKMonitorUtils
-dontwarn com.bytedance.keva.Keva
-dontwarn com.bytedance.keva.KevaBuilder
-dontwarn com.bytedance.keva.KevaMonitor
