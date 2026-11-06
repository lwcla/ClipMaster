package com.cla.clip.master.ui.page.image.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.R
import com.cla.clip.master.ui.widget.TitleBar

/** 图片提取页真实标题和内容约束；探测、广告守卫及数据观察仍由页面调用方负责。 */
@Composable
internal fun ImageExtractPageScaffold(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Scaffold(modifier = modifier, topBar = { TitleBar(stringResource(R.string.base_general_image_extract_title), onBack) }) { padding ->
        // 正文消费标题和系统边距，再接入页面触摸守卫；与原页面的修饰符顺序一致。
        Box(Modifier.fillMaxSize().padding(padding).then(contentModifier), content = content)
    }
}

/** 批次摘要与图片网格同屏分配有界高度；只保存摘要滚动，网格滚动仍由原页面持有。 */
@Composable
internal fun ImageBatchContentLayout(
    summary: @Composable () -> Unit,
    grid: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        // 沿用 MediaAdGrid 的 96dp 图片操作基线；极矮窗口分配一半，避免摘要完全不可达。
        val reservedGridHeight = minOf(96.dp, maxHeight * 0.5f)
        // 摘要短时自然测量，长时只在自己的区域滚动，不再耗尽后续网格的高度。
        val summaryHeightLimit = maxHeight - reservedGridHeight
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().heightIn(max = summaryHeightLimit).verticalScroll(rememberScrollState())) {
                summary()
            }
            Box(Modifier.fillMaxWidth().weight(1f)) { grid() }
        }
    }
}
