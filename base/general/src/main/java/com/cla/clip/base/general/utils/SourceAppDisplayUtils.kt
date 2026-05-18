package com.cla.clip.base.general.utils

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.cla.clip.base.general.R
import com.cla.clip.base.general.dao.SourceAppData

/**
 * 在 Compose UI 中获取来源 App 实体的展示名。
 *
 * `SourceAppData` 是来源 App 信息的主要载体，使用扩展方法能让搜索筛选等直接持有实体的 UI 调用更自然；
 * 名称缺失时会统一回退到字符串资源里的“未知”。
 */
@Composable
fun SourceAppData.displayName(): String {
    return appName.toSourceAppDisplayName()
}

/**
 * 在非 Compose 场景中获取来源 App 实体的展示名。
 *
 * 后台任务、普通工具函数或 ViewModel 没有 Composition，必须由调用方传入 `Context` 读取字符串资源；
 * 方法不会保存该 Context，只同步读取缺省文案。
 */
fun SourceAppData.displayName(context: Context): String {
    return appName.toSourceAppDisplayName(context)
}

/**
 * 在 Compose UI 中把可空来源 App 名称转换成用户可见名称。
 *
 * 剪贴列表 item 目前只持有已经拍平的 `String? appName`，因此提供名称级扩展，
 * 让列表也能复用同一套空名称兜底规则，而不需要反向构造 `SourceAppData`。
 */
@Composable
fun String?.toSourceAppDisplayName(): String {
    return toSourceAppDisplayName(LocalContext.current)
}

/**
 * 在非 Compose 场景中把可空来源 App 名称转换成用户可见名称。
 *
 * 名称存在时会先去除首尾空白；名称为 null、空串或只有空白字符时返回字符串资源里的“未知”。
 */
fun String?.toSourceAppDisplayName(context: Context): String {
    return this?.trim()?.takeIf { it.isNotEmpty() }
        ?: context.getString(R.string.base_general_unknow)
}
