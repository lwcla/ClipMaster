package com.cla.clip.base.general.utils

import java.io.File

/**
 * 判断本地缓存图标文件是否适合交给 Provider 复用或 UI 加载。
 *
 * 只把真实文件、可读且非空的路径视为可用，避免空文件或目录被误判为 cache hit 后阻止 Shizuku 重新补传图标。
 */
fun File.isUsableCachedIconFile(): Boolean {
    return isFile && canRead() && length() > 0L
}
