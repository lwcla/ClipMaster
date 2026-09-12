package com.cla.clip.feature.torrent.search.ui.details

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient

/** 构造详情图片专用加载器与禁止自动重定向的网络客户端，不修改应用全局 Coil 配置。 */
internal object TorrentDetailsImageLoaderFactory {
    /** 构造只服务详情图片的 Coil 加载器，所有网络抓取都停留在首个响应。 */
    fun createImageLoader(
        /** context 仅用于取得不会泄漏 Activity 的应用 Context。 */
        context: Context,
    ): ImageLoader {
        /** networkClient 是关闭普通和跨协议自动重定向的 feature 内客户端。 */
        val networkClient = createNetworkClient()
        return ImageLoader.Builder(context.applicationContext)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = networkClient))
            }
            .build()
    }

    /** 在保留调用方 TLS 等配置的同时强制关闭两类自动重定向。 */
    fun createNetworkClient(
        /** builder 允许 JVM 测试注入合成证书，生产默认使用隔离的新客户端。 */
        builder: OkHttpClient.Builder = OkHttpClient.Builder(),
    ): OkHttpClient = builder
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}
