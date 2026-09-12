package com.cla.clip.feature.torrent.search.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.ImageLoader
import coil3.request.ImageRequest
import com.cla.clip.feature.torrent.search.R
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.model.contract.TorrentFile
import com.cla.clip.feature.torrent.search.ui.component.torrentCategoryLabel
import java.text.DateFormat
import java.util.Date

/** 原生 Torrent 详情页；不提供 06E 才负责的磁力与下载动作。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TorrentDetailsScreen(
    /** 已经过 URL、身份与富文本安全收窄的详情状态。 */
    state: TorrentDetailsUiState,
    /** 由当前 NavBackStackEntry 直接驱动的图片 Composition 许可。 */
    imagesActive: Boolean,
    /** 固定失败态的显式重试事件。 */
    onRetry: () -> Unit,
    /** 返回上级列表事件。 */
    onBack: () -> Unit,
    /** 页面外部布局约束。 */
    modifier: Modifier = Modifier,
) {
    /** context 只用于构造不会修改应用全局配置的详情图片加载器。 */
    val context = LocalContext.current
    /** imageLoader 以详情 Composition 为边界复用，并在离开详情时关闭。 */
    val imageLoader = remember(context) { TorrentDetailsImageLoaderFactory.createImageLoader(context) }
    DisposableEffect(imageLoader) {
        onDispose { imageLoader.shutdown() }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.torrent_search_torrent_details_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.torrent_search_action_back),
                        )
                    }
                },
            )
        },
    ) { /** paddingValues 是 Scaffold 为系统栏与顶部栏提供的安全内边距。 */ paddingValues ->
        when (state.phase) {
            TorrentDetailsPhase.IDLE,
            TorrentDetailsPhase.LOADING,
            -> TorrentDetailsLoading(modifier = Modifier.fillMaxSize().padding(paddingValues))

            TorrentDetailsPhase.ERROR -> TorrentDetailsError(
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize().padding(paddingValues),
            )

            TorrentDetailsPhase.CONTENT -> state.details?.let { /** details 是通过稳定身份校验的完整详情。 */ details ->
                TorrentDetailsContent(
                    details = details,
                    description = state.description,
                    posterUrl = state.posterUrl,
                    screenshotUrls = state.screenshotUrls,
                    imagesActive = imagesActive,
                    imageLoader = imageLoader,
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                )
            } ?: TorrentDetailsError(
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize().padding(paddingValues),
            )
        }
    }
}

/** 详情加载占位，离页取消由生命周期 ViewModel 负责。 */
@Composable
private fun TorrentDetailsLoading(
    /** 加载占位布局约束。 */
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** 详情失败态只展示固定文案，不回显外部异常正文。 */
@Composable
private fun TorrentDetailsError(
    /** 重试当前内存候选事件。 */
    onRetry: () -> Unit,
    /** 失败态布局约束。 */
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.torrent_search_torrent_details_state_unavailable_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.torrent_search_torrent_details_state_unavailable_description),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onRetry) {
            Text(stringResource(R.string.torrent_search_button_try_again))
        }
    }
}

/** 展示详情基础字段、安全正文、受限媒体、文件与 tracker。 */
@Composable
private fun TorrentDetailsContent(
    /** 已通过身份校验的领域详情。 */
    details: TorrentDetails,
    /** 已移除可执行内容并限制资源数的正文。 */
    description: SafeRichTextDocument?,
    /** 已通过同源 HTTPS 校验的海报 URL。 */
    posterUrl: String?,
    /** 已通过同源 HTTPS 校验且去重的截图 URL。 */
    screenshotUrls: List<String>,
    /** 直接生命周期状态决定是否创建所有网络图片子 Composition。 */
    imagesActive: Boolean,
    /** 详情 feature 专用且禁止自动重定向的 Coil 加载器。 */
    imageLoader: ImageLoader,
    /** 详情内容布局约束。 */
    modifier: Modifier = Modifier,
) {
    /** descriptionImages 是正文解析出的受限同源图片。 */
    val descriptionImages = description?.imageUrls.orEmpty()
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "details_title") {
            Text(text = details.name, style = MaterialTheme.typography.headlineSmall)
        }
        if (TorrentDetailsImagePresentationPolicy.shouldComposeImages(imagesActive)) {
            posterUrl?.let { /** safePosterUrl 是已校验同源 HTTPS 的海报。 */ safePosterUrl ->
                item(key = "details_poster") { ConstrainedNetworkImage(safePosterUrl, imageLoader) }
            }
        }
        item(key = "details_info") { TorrentDetailsInfo(details) }
        if (!description?.text.isNullOrBlank()) {
            item(key = "details_description_title") {
                Text(
                    text = stringResource(R.string.torrent_search_torrent_details_title_description),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            item(key = "details_description") { SafeRichText(description!!) }
        }
        if (TorrentDetailsImagePresentationPolicy.shouldComposeImages(imagesActive)) {
            items(
                items = descriptionImages,
                key = { /** imageUrl 是正文已规范化的图片稳定地址。 */ imageUrl ->
                    TorrentDetailsImagePresentationPolicy.lazyItemKey(TorrentDetailsImageSection.DESCRIPTION, imageUrl)
                },
            ) { /** imageUrl 是当前受限正文图片。 */ imageUrl ->
                ConstrainedNetworkImage(imageUrl, imageLoader)
            }
        }
        if (screenshotUrls.isNotEmpty()) {
            item(key = "details_screenshots_title") {
                Text(
                    text = stringResource(R.string.torrent_search_torrent_details_title_screenshots),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (TorrentDetailsImagePresentationPolicy.shouldComposeImages(imagesActive)) {
                items(
                    items = screenshotUrls,
                    key = { /** imageUrl 是截图的规范化稳定地址。 */ imageUrl ->
                        TorrentDetailsImagePresentationPolicy.lazyItemKey(TorrentDetailsImageSection.SCREENSHOT, imageUrl)
                    },
                ) { /** imageUrl 是当前受限截图。 */ imageUrl ->
                    ConstrainedNetworkImage(imageUrl, imageLoader)
                }
            }
        }
        if (details.files.isNotEmpty()) {
            item(key = "details_files_title") {
                Text(
                    text = stringResource(R.string.torrent_search_torrent_details_title_files),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            itemsIndexed(
                items = details.files,
                key = { /** index 区分来源返回的重复文件项，file 提供内容稳定部分。 */ index, file -> "$index:${file.name}:${file.sizeBytes}" },
            ) { /** index 只用于稳定区分重复条目，file 是当前详情文件。 */ _, file ->
                TorrentFileRow(file)
            }
        }
        if (details.trackers.isNotEmpty()) {
            item(key = "details_trackers_title") {
                Text(
                    text = stringResource(R.string.torrent_search_torrent_details_title_trackers),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            itemsIndexed(
                items = details.trackers,
                key = { /** index 区分来源返回的重复 Tracker，tracker 提供内容稳定部分。 */ index, tracker -> "$index:${tracker.address}" },
            ) { /** index 只用于稳定区分重复条目，tracker 是当前详情 Tracker。 */ _, tracker ->
                Text(text = tracker.address, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 基础字段卡使用资源化标签并省略未知可选值。 */
@Composable
private fun TorrentDetailsInfo(
    /** 待展示的完整详情。 */
    details: TorrentDetails,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.torrent_search_torrent_details_title_info),
                style = MaterialTheme.typography.titleMedium,
            )
            DetailsValueRow(R.string.torrent_search_torrent_details_label_provider, details.providerName)
            details.category?.let { /** category 是已归一化的可选分类。 */ category ->
                DetailsValueRow(R.string.torrent_search_torrent_details_label_category, torrentCategoryLabel(category))
            }
            details.size?.let { /** size 是 Provider 格式化后的短大小文本。 */ size ->
                DetailsValueRow(R.string.torrent_search_torrent_details_label_file_size, size)
            }
            details.seeders?.let { /** seeders 是可选做种数量。 */ seeders ->
                DetailsValueRow(R.string.torrent_search_torrent_details_label_seeders, seeders.toString())
            }
            details.peers?.let { /** peers 是可选下载节点数量。 */ peers ->
                DetailsValueRow(R.string.torrent_search_torrent_details_label_peers, peers.toString())
            }
            details.uploadEpochMillis?.let { /** epochMillis 是上传时间 UTC 毫秒。 */ epochMillis ->
                DetailsValueRow(R.string.torrent_search_torrent_details_label_upload_date, formatEpochMillis(epochMillis))
            }
            details.uploader?.let { /** uploader 是外部来源提供的发布者展示文本。 */ uploader ->
                DetailsValueRow(R.string.torrent_search_torrent_details_label_uploader, uploader)
            }
            details.lastCheckedEpochMillis?.let { /** epochMillis 是来源最后检查时间 UTC 毫秒。 */ epochMillis ->
                DetailsValueRow(R.string.torrent_search_torrent_details_label_last_checked, formatEpochMillis(epochMillis))
            }
            DetailsValueRow(R.string.torrent_search_torrent_details_label_info_hash, details.infoHash)
        }
    }
}

/** 展示单个资源化标签和值。 */
@Composable
private fun DetailsValueRow(
    /** 字段标签资源。 */
    labelResource: Int,
    /** 已收窄的字段展示值。 */
    value: String,
) {
    Text(
        text = stringResource(
            R.string.torrent_search_torrent_details_field_format,
            stringResource(labelResource),
            value,
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** 把受限正文转换为只有 HTTP/HTTPS 点击注解的 Compose 文本。 */
@Composable
private fun SafeRichText(
    /** 解析器输出的纯文本与安全链接范围。 */
    document: SafeRichTextDocument,
) {
    /** linkColor 跟随当前主题，避免富文本自带样式注入。 */
    val linkColor = MaterialTheme.colorScheme.primary
    /** annotatedText 仅附加解析器批准的 URL 注解。 */
    val annotatedText = remember(document, linkColor) { buildSafeAnnotatedString(document, linkColor) }
    /** uriHandler 由平台处理批准的 HTTP/HTTPS 外链。 */
    val uriHandler = LocalUriHandler.current
    ClickableText(
        text = annotatedText,
        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        onClick = { /** offset 是用户点击的正文字符下标。 */ offset ->
            annotatedText.getStringAnnotations(SAFE_URL_TAG, offset, offset).firstOrNull()?.let { /** annotation 是解析器批准的 URL 注解。 */ annotation ->
                runCatching { uriHandler.openUri(annotation.item) }
            }
        },
    )
}

/** 构建不携带 HTML 样式或可执行协议的 AnnotatedString。 */
private fun buildSafeAnnotatedString(
    /** 已受限的富文本模型。 */
    document: SafeRichTextDocument,
    /** 当前主题链接颜色。 */
    linkColor: Color,
): AnnotatedString = buildAnnotatedString {
    append(document.text)
    document.links.forEach { /** link 是已验证协议与范围的安全链接。 */ link ->
        if (link.start in 0..length && link.endExclusive in link.start..length) {
            addStringAnnotation(SAFE_URL_TAG, link.url, link.start, link.endExclusive)
            addStyle(
                SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                link.start,
                link.endExclusive,
            )
        }
    }
}

/** 使用固定请求像素和布局上限加载已校验图片，离开 Composition 时 Coil 自动取消。 */
@Composable
private fun ConstrainedNetworkImage(
    /** 已通过同源 HTTPS 校验的图片 URL。 */
    imageUrl: String,
    /** 专用加载器关闭自动重定向，避免首个同源地址跳出白名单。 */
    imageLoader: ImageLoader,
) {
    /** context 只用于构建当前 Composition 的 Coil 请求。 */
    val context = LocalContext.current
    /** request 显式限制解码尺寸，防止原图尺寸放大内存。 */
    val request = remember(imageUrl, context) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .size(1_200, 800)
            .build()
    }
    AsyncImage(
        model = request,
        imageLoader = imageLoader,
        contentDescription = null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp),
        contentScale = ContentScale.Fit,
    )
}

/** 展示文件名与可选字节数，不对 Provider 文本执行格式指令。 */
@Composable
private fun TorrentFileRow(
    /** 当前文件领域条目。 */
    file: TorrentFile,
) {
    /** fileText 使用命名资源格式化可选字节数，避免在 Kotlin 中拼接 UI 文案。 */
    val fileText = file.sizeBytes?.let { /** sizeBytes 是非格式化文件字节数。 */ sizeBytes ->
        stringResource(R.string.torrent_search_torrent_details_file_with_size_format, file.name, sizeBytes)
    } ?: file.name
    Text(text = fileText, style = MaterialTheme.typography.bodyMedium)
}

/** 以设备区域设置格式化 UTC epochMillis 展示值。 */
private fun formatEpochMillis(
    /** UTC epochMillis 时间点。 */
    epochMillis: Long,
): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))

/** 富文本 URL 注解固定标签，只在当前文件内解释。 */
private const val SAFE_URL_TAG = "safe_url"
