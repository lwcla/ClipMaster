package com.cla.clip.master.ui.page.privacy

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.cla.clip.base.general.utils.logW
import com.cla.clip.base.general.utils.toast
import com.cla.clip.master.R
import com.cla.clip.master.ui.widget.CenteredStateContent
import com.cla.clip.master.ui.widget.InlineErrorState
import com.cla.clip.master.ui.widget.InlineLoadingState
import com.cla.clip.master.ui.widget.SecondaryPageScaffold
import com.cla.clip.master.ui.widget.document.OfflineHtmlDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 隐私政策二级页；按需读取随包正文，不依赖广告同意、网络、数据库或权限。 */
@Composable
internal fun PrivacyPolicyPage(
    // 返回由现有导航栈处理，文档内部锚点不增加应用导航层级。
    onBack: () -> Unit,
) {
    // 当前页面 Context 只用于 assets、外部跳转和用户提示，不交给 ViewModel。
    val context = LocalContext.current
    // 提示任务属于页面，离开组合后自动取消。
    val scope = rememberCoroutineScope()
    // null 表示读取中；读取失败明确展示错误，不把空字符串伪装为有效政策。
    val document by produceState<Result<String>?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("privacy-policy.html").bufferedReader(Charsets.UTF_8).use { it.readText() } }
                .onFailure { error ->
                    logW("PrivacyPolicy") { "隐私政策读取失败 reasonCode=asset_read_failed type=${error.javaClass.simpleName}" }
                }
        }
    }
    // WebView 主文档错误只存在于本次阅读，不保存到用户设置或备份。
    var renderFailed by remember { mutableStateOf(false) }
    SecondaryPageScaffold(title = stringResource(R.string.host_privacy_policy_title), onBack = onBack) { padding ->
        // 所有状态使用同一可用区域，避免标题和导航栏覆盖正文。
        val contentModifier = Modifier.fillMaxSize().padding(padding)
        when {
            document == null -> CenteredStateContent(contentModifier) {
                InlineLoadingState(stringResource(R.string.host_privacy_policy_loading))
            }
            document?.isFailure == true || renderFailed -> CenteredStateContent(contentModifier) {
                InlineErrorState(stringResource(R.string.host_privacy_policy_load_failed))
            }
            else -> OfflineHtmlDocument(
                html = document?.getOrNull().orEmpty(),
                modifier = contentModifier,
                // 只有正文中明确点击的受支持链接到达此处；没有应用处理时保留阅读页并提示。
                onOpenExternalLink = { url ->
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        .onFailure { error ->
                            logW("PrivacyPolicy") { "政策外链打开失败 reasonCode=external_open_failed type=${error.javaClass.simpleName}" }
                            scope.launch { context.toast(R.string.host_privacy_policy_open_failed) }
                        }
                },
                // 只记录系统数值错误码，不记录页面内容、邮箱、URL 或异常原文。
                onLoadError = { code ->
                    renderFailed = true
                    logW("PrivacyPolicy") { "政策正文展示失败 reasonCode=document_load_failed code=$code" }
                },
            )
        }
    }
}
