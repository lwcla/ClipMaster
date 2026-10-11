package com.cla.clip.master.ui.widget.sourceapp

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import com.cla.clip.master.R

/** 独立图标入口按包名加载；无需同时取色的菜单/安装行直接复用。 */
@Composable
internal fun AppIcon(
    /** 非空包名才允许系统读取，未知来源直接占位。 */
    packageName: String?,
    /** 默认加载器服务 Preview，实际页面显式提供 Manager adapter。 */
    loader: AppIconLoader = EmptyAppIconLoader,
    /** 尺寸与布局语义由调用方控制。 */
    modifier: Modifier = Modifier,
    /** 保留组合的页面需额外传实际可见标记。 */
    visibleToUser: Boolean = true,
) {
    /** 图像只存于当前 UI 会话，不放回业务列表对象。 */
    val entry = rememberAppIconEntry(packageName, loader, visibleToUser)
    AppIconImage(entry?.icon, modifier)
}

/** 纯绘制小图或默认资源；只包装像素，不发请求或依赖 Manager。 */
@Composable
internal fun AppIconImage(
    /** 局部会话提供的已加载图像，null 使用统一默认资源。 */
    icon: Bitmap?,
    /** 保持卡片/菜单/安装行各自尺寸。 */
    modifier: Modifier = Modifier,
) {
    if (icon == null) {
        Image(painterResource(R.drawable.host_ic_app_icon_default), null, modifier, contentScale = ContentScale.Fit)
    } else {
        /** 仅包装已有像素，不复制图片或触发文件读取。 */
        val image = remember(icon) { icon.asImageBitmap() }
        Image(image, null, modifier, contentScale = ContentScale.Fit)
    }
}

/** 亮暗占位预览不连接 Hilt、PackageManager 或文件。 */
@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AppIconPreview() {
    AppIconImage(null, Modifier.size(40.dp))
}
