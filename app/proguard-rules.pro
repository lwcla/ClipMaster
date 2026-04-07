# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

#某个依赖里“可选”支持了 re2j，但你项目并没引入 re2j。
#R8 检测到引用后给警告/报错（具体是否失败取决于构建配置）。
#AGP 才会让你“加 missing_rules 或补类”。
#如果你确认运行时不会走到这段 re2j 逻辑（最常见）
#把 missing_rules.txt 里的两行加到 app/proguard-rules.pro：
-dontwarn com.google.re2j.Matcher
-dontwarn com.google.re2j.Pattern