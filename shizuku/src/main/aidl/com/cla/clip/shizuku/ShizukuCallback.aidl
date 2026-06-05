package com.cla.clip.shizuku;

interface ShizukuCallback {
    // 无副作用探活入口，只用于确认 app 主进程 Binder callback 是否仍可达。
    boolean pingAppProcess();
}
