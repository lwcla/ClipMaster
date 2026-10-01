package com.cla.clip.master.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cla.clip.master.ui.theme.ClipMasterTheme
import com.cla.clip.master.ui.theme.ClipMasterThemeTokens
import com.cla.clip.base.general.R as BaseR

/** 顶部内容与普通标题栏同为 48dp，按钮同时保持最小触控热区。 */
private val SearchTopBarContentHeight = 48.dp

/**
 * 顶部无边框搜索输入，取代整条标题栏，不承载业务搜索、历史、导航或持久化。
 * query 唯一来源为调用方；inputModifier 用于接入焦点请求，组件不在重组时主动弹键盘。
 * 返回、清空与提交只是事件，是否退出搜索或收键盘由页面决定。
 */
@Composable
internal fun SearchTopBar(
    /** 当前输入原文，不在展示层 trim 或截断。 */
    query: String,
    /** 调用方资源化的空输入提示。 */
    placeholder: String,
    /** 返回语义由调用方区分退出搜索或离页。 */
    backContentDescription: String,
    /** 清空输入的读屏提示，非空输入时才出现。 */
    clearContentDescription: String,
    /** 输入变化由调用方更新唯一查询状态。 */
    onQueryChange: (String) -> Unit,
    /** 顶部返回按钮事件，不直接操作导航栈。 */
    onBack: () -> Unit,
    /** 清空事件可由页面同时维持输入焦点。 */
    onClear: () -> Unit,
    /** IME 搜索事件，是否提交请求由页面决定。 */
    onSubmit: () -> Unit,
    /** 整体布局接口，状态栏避让只在本组件处理一次。 */
    modifier: Modifier = Modifier,
    /** 仅输入区域的修饰接口，便于调用方接入 FocusRequester。 */
    inputModifier: Modifier = Modifier,
) {
    /** 沿用共享 tiny/medium 间距，与其他标题和表单保持一致，不维护组件私有间距体系。 */
    val spacing = ClipMasterThemeTokens.tokens.spacing
    Box(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = SearchTopBarContentHeight).padding(horizontal = spacing.tiny),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(SearchTopBarContentHeight)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backContentDescription)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                // 输入自身覆盖整条内容高度，收键盘后点击文字上下空白也能重新聚焦。
                modifier = Modifier.weight(1f).heightIn(min = SearchTopBarContentHeight)
                    .padding(horizontal = spacing.medium).then(inputModifier),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // 输入提交只回调，避免把某个页面的实时筛选规则写进通用组件。
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                // innerTextField 是平台输入节点，提示只覆盖空态而不替换输入的焦点或选择语义。
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear, modifier = Modifier.size(SearchTopBarContentHeight)) {
                    Icon(Icons.Default.Close, contentDescription = clearContentDescription)
                }
            }
        }
        // 分隔线覆盖底边，不为搜索态额外增加一行或改变标题栏内容高度。
        HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 空输入预览检查提示、返回按钮与顶部状态栏避让，交互在 Preview 中不执行业务动作。 */
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SearchTopBarEmptyPreview() {
    ClipMasterTheme { SearchTopBarPreviewContent(query = "") }
}

/** 窄屏大字体检查长输入仍保留返回与清空热区，不使用真实用户关键词。 */
@Preview(showBackground = true, widthDp = 280, fontScale = 1.5f)
@Composable
private fun SearchTopBarNarrowPreview() {
    ClipMasterTheme { SearchTopBarPreviewContent(query = stringResource(com.cla.clip.master.R.string.host_preview_app_package)) }
}

/** 深色空态用于确认提示、光标和分隔线对比度。 */
@Preview(showBackground = true, widthDp = 360, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SearchTopBarDarkPreview() {
    ClipMasterTheme { SearchTopBarPreviewContent(query = "") }
}

/** 预览只提供资源化合成内容和无副作用回调，不持有页面 ViewModel。 */
@Composable
private fun SearchTopBarPreviewContent(
    /** 合成输入用于布局检查，不涉及历史记录或安装读取。 */
    query: String,
) {
    SearchTopBar(
        query = query,
        placeholder = stringResource(BaseR.string.base_general_clip_source_block_search_hint),
        backContentDescription = stringResource(com.cla.clip.master.R.string.host_exit_search),
        clearContentDescription = stringResource(BaseR.string.base_general_clear_search_keyword),
        // 各预览回调只占位，不发出搜索、导航或设置写入。
        onQueryChange = {}, onBack = {}, onClear = {}, onSubmit = {},
    )
}
