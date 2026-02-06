package com.cla.clip.shizuku;

// 必须显式 import
import android.graphics.Bitmap;

interface ShizukuCallback {
    void onOpNoted(String packageName,String appName,in Bitmap appIcon);
}