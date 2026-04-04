# Keep only ClipboardShizukuService class and its Context constructor for Shizuku reflection/instantiation
-keep class com.cla.clip.shizuku.ClipboardShizukuService {
    public <init>(android.content.Context);
}

-keep class android.app.AppOpsManagerHidden { *; }
-keep interface android.app.AppOpsManagerHidden$OnOpNotedListener { *; }
-keep class * implements android.app.AppOpsManagerHidden$OnOpNotedListener { *; }
