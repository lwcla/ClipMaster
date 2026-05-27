package com.cla.clip.master.provider

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Provider 访问 Hilt 单例图的入口。
 *
 * ContentProvider 不是当前项目的 Hilt AndroidEntryPoint，使用 EntryPoint 可以保持 Provider 入口轻薄，同时复用现有单例依赖。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ClipboardBridgeEntryPoint {
    /** Provider 剪贴板读取协调器。 */
    fun clipboardBridgeReadCoordinator(): ClipboardBridgeReadCoordinator

    /** Provider 图标异步提交协调器。 */
    fun clipboardBridgeIconCommitter(): ClipboardBridgeIconCommitter

    /** Provider 图标临时传输目录管理器。 */
    fun clipboardBridgeIconStore(): ClipboardBridgeIconStore
}
