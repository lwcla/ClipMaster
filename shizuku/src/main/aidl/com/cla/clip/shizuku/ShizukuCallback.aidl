package com.cla.clip.shizuku;

interface ShizukuCallback {
    void onOpNoted(String op,String packageName,String appName,in byte[] appIcon);
}