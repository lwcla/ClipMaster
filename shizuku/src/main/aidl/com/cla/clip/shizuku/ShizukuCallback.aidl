package com.cla.clip.shizuku;

interface ShizukuCallback {
    void onOpNoted(String op, int uid, String packageName,String appName, String attributionTag, int flags, int result);
}