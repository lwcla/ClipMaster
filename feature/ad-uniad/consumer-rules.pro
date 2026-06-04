# uni-ad 官方 AAR 已经混淆，本模块保留 SDK facade 名称，便于 R8 包定位公开 API 和崩溃归因。
-keep class com.cla.clip.feature.ad.uniad.UniAdSdkDirectClient { *; }

# uni-ad 原生 SDK、章鱼和泛连通过 AAR 形式接入；release/R8 合入前必须复核这些规则是否仍然必要。
-keep class io.dcloud.ads.** { *; }
-keep class io.dcloud.openapi.** { *; }
-keep interface io.dcloud.ads.** { *; }
-keep interface io.dcloud.openapi.** { *; }

# DCloud/渠道 AAR 可能在不同版本中反射访问兼容类；后续升级必须通过 minifyReleaseWithR8 重新生成和收敛规则。
-dontwarn com.kwad.**
-dontwarn com.qq.e.**
-dontwarn com.sigmob.**
-dontwarn com.baidu.mobads.**
-dontwarn com.bytedance.sdk.openadsdk.**
-dontwarn com.getkeepsafe.relinker.**
-dontwarn com.tencent.mm.opensdk.**
-dontwarn org.apache.commons.codec.**
