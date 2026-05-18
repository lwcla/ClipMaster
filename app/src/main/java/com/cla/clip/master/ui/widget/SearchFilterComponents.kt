package com.cla.clip.master.ui.widget

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 单行搜索输入框默认高度。
 *
 * 固定高度可以避免粘贴多行内容或字体测量变化时把下方结果列表挤小。
 */
private val SEARCH_INPUT_FIELD_HEIGHT = 56.dp

/**
 * 通用搜索输入框。
 *
 * 组件只负责单行输入、清空按钮和键盘搜索动作；查询规整、保存历史和执行搜索由调用方处理。
 */
@Composable
internal fun SearchInputField(
    query: String,
    onQueryChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    placeholder: String,
    clearContentDescription: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .heightIn(min = SEARCH_INPUT_FIELD_HEIGHT, max = SEARCH_INPUT_FIELD_HEIGHT)
            .onFocusChanged { onFocusChange(it.isFocused) },
        singleLine = true,
        maxLines = 1,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null
            )
        },
        trailingIcon = {
            if (query.isNotBlank()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = clearContentDescription
                    )
                }
            }
        },
        placeholder = { Text(placeholder) }
    )
}

/**
 * 横向互斥筛选 Chip 选项。
 *
 * `value` 由调用方保存，组件不解析筛选语义，只负责显示和回传点击。
 */
internal data class FilterChipOption<T>(
    /** 筛选项稳定值。 */
    val value: T,

    /** 筛选项标题，必须由调用方资源化后传入。 */
    val label: String,
)

/**
 * 通用横向筛选 Chip 行。
 *
 * 窄屏放不下时允许横向滚动，比压缩或截断互斥选项更容易读。
 */
@Composable
internal fun <T> HorizontalFilterChips(
    options: List<FilterChipOption<T>>,
    selectedValue: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option.value == selectedValue,
                onClick = { onSelected(option.value) },
                label = {
                    Text(
                        text = option.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            )
        }
    }
}
