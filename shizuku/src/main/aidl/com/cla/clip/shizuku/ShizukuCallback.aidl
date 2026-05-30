package com.cla.clip.shizuku;

// 必须显式 import
import android.graphics.Bitmap;

interface ShizukuCallback {
    // 旧 AIDL 链路的剪贴来源投递入口，Provider 直读模式不得用它保存同一条剪贴内容。
    void onOpNoted(String packageName,String appName,in Bitmap appIcon,String appIconHash);
    // 无副作用探活入口，只用于确认 app 主进程 Binder callback 是否仍可达。
    boolean pingAppProcess();
}
