# Keep only ClipboardShizukuService class and its Context constructor for Shizuku reflection/instantiation
-keep class com.cla.clip.shizuku.ClipboardShizukuService {
    public <init>(android.content.Context);
}

-keep class android.app.AppOpsManagerHidden { *; }
-keep class android.app.AppOpsManagerHidden$OnOpNotedListener { *; }
-keepclassmembers class * implements android.app.AppOpsManagerHidden$OnOpNotedListener {
    public void onOpNoted(java.lang.String,int,java.lang.String,java.lang.String,int,int);
    public void onOpNoted(java.lang.String,int,java.lang.String,java.lang.String,int,int,int);
}