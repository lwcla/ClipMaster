package com.cla.clip.master.ui.widget.media

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cla.clip.feature.ad.api.AdPlacement
import com.cla.clip.master.ad.PageAdSession
import com.cla.clip.master.ad.PageAdSlot

/**
 * 固定媒体广告的尺寸与释放边界；自然高度放不下时不放置素材，业务内容优先占用空间。
 * 超高状态按页面会话及广告位锁存，避免移除后重复加载；新访问或新业务阶段重新评估。
 */
@Composable
internal fun MediaAdFooter(
    // 调用页面持有的访问会话，底栏不另建广告身份。
    session: PageAdSession,
    // 当前业务阶段使用的广告位，阶段变化时重新评估空间。
    placement: AdPlacement,
    // 标题和操作区之外，业务内容与底栏共享的实际有界高度，单位为像素。
    availableHeightPx: Int,
    // 必须为可滚动业务内容保留的最小视口，单位为像素。
    minimumContentHeightPx: Int,
    // 权限或预览等临时阻塞状态，仅沿用共享宿主请求与呈现许可。
    blocked: Boolean,
    // 页面允许改变布局时才呈现新素材，避免滚动或按压期间突变。
    canReveal: Boolean,
    // 外层布局修饰，不向底栏引入页面导航或业务实体依赖。
    modifier: Modifier = Modifier,
    // 宿主留白由调用方替换；默认维持图片网格的 12dp 边界，不叠加两份边距。
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp),
) {
    // 同一访问的同一广告位不重试；阶段切换的独立广告位不能被前阶段超高素材锁死。
    var oversized by remember(session, placement) { mutableStateOf(false) }
    // 页面本身过小时不请求广告；已超高时移除子组合，让 PageAdSlot 释放原生资源。
    if (oversized || !MediaAdFooterPolicy.fits(availableHeightPx, 0, minimumContentHeightPx)) return

    MediaAdFooterLayout(
        availableHeightPx = availableHeightPx,
        minimumContentHeightPx = minimumContentHeightPx,
        contentPadding = contentPadding,
        // 锁存超高状态，下一次组合释放原生资源且同一广告位不重试。
        onOversized = { oversized = true },
        modifier = modifier,
    ) {
        PageAdSlot(
            session = session,
            placement = placement,
            slotIndex = 0,
            requestEnabled = !blocked,
            canReveal = !blocked && canReveal,
            // 固定槽没有 Lazy 离屏；小屏主动移除时及时销毁超高素材。
            retainOnDispose = false,
        )
    }
}
