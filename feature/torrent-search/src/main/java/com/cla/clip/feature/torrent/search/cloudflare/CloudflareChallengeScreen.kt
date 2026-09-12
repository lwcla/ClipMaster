package com.cla.clip.feature.torrent.search.cloudflare

import android.net.http.SslError
import android.os.Build
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.cla.clip.feature.torrent.search.R

/** 仅用于已知 Cloudflare Provider 的受限挑战 WebView。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudflareChallengeScreen(
    /** 从稳定 Provider ID 解析出的已知目标。 */
    target: CloudflareChallengeTarget,
    /** 返回 Provider 管理页。 */
    onBack: () -> Unit,
    /** 页面外部布局约束。 */
    modifier: Modifier = Modifier,
) {
    /** navigationPolicy 把全部页面内导航限制在初始 scheme、host 与端口。 */
    val navigationPolicy = CloudflareNavigationPolicy(target.url)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.torrent_search_cloudflare_title_format, target.providerName)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.torrent_search_action_back))
                    }
                },
            )
        },
    ) { /** contentPadding 是系统栏与顶部栏提供的安全边距。 */ contentPadding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            factory = { /** context 只用于创建当前目的地独占 WebView。 */ context ->
                WebView(context).apply {
                    /** webSettings 只在挑战页开启脚本，并关闭文件、内容、多窗口和混合内容能力。 */
                    val webSettings = settings
                    webSettings.javaScriptEnabled = true
                    webSettings.domStorageEnabled = true
                    webSettings.allowFileAccess = false
                    webSettings.allowContentAccess = false
                    webSettings.javaScriptCanOpenWindowsAutomatically = false
                    webSettings.setSupportMultipleWindows(false)
                    webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WebView.startSafeBrowsing(context, null)
                    webViewClient = object : WebViewClient() {
                        /** Android 7+ 导航只允许精确同源 HTTP/HTTPS。 */
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                            !navigationPolicy.allows(request?.url?.toString())

                        /** 兼容旧回调并应用相同来源策略。 */
                        @Suppress("DEPRECATION")
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = !navigationPolicy.allows(url)

                        /** TLS 证书错误必须取消，不能由挑战页绕过。 */
                        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                            handler?.cancel()
                        }

                        /** 页面完成后请求 WebView 把挑战 Cookie 刷入其标准存储。 */
                        override fun onPageFinished(view: WebView?, url: String?) {
                            CookieManager.getInstance().flush()
                        }
                    }
                    loadUrl(target.url)
                }
            },
            update = { /** webView 是仍属于当前目的地的实例。 */ webView ->
                if (webView.url == null && navigationPolicy.allows(target.url)) webView.loadUrl(target.url)
            },
            onRelease = { /** webView 离开目的地后必须停止网络并销毁。 */ webView ->
                webView.stopLoading()
                webView.webChromeClient = null
                webView.webViewClient = WebViewClient()
                webView.destroy()
            },
        )
    }
}

/** 非法、未知或未声明保护的 Provider 路由固定错误页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudflareChallengeUnavailableScreen(
    /** 返回 Provider 管理页。 */
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.torrent_search_cloudflare_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.torrent_search_action_back))
                    }
                },
            )
        },
    ) { /** contentPadding 是错误页系统安全边距。 */ contentPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.torrent_search_cloudflare_unavailable))
        }
    }
}
