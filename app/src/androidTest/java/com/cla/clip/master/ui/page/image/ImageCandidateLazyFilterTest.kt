package com.cla.clip.master.ui.page.image

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.request.ImageRequest
import com.cla.clip.master.ui.page.image.component.ImageCandidateToolbar
import com.cla.clip.master.ui.page.image.component.ImageCandidateToolbarState
import com.cla.clip.master.ui.widget.SelectableImageTile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 Lazy 网格与 Coil 回调复现滚动后才失败，不把全部失败预先塞进选择对象。 */
@RunWith(AndroidJUnit4::class)
class ImageCandidateLazyFilterTest {
    /** 独立设备宿主，不访问真实网页、下载或应用数据库。 */
    @get:Rule val compose = createComposeRule()

    /** 开关提前开启，离屏图片之后失败自动取消，手动勾选仍可保留到下次全选。 */
    @Test fun scrollingToLaterFailureFiltersAndSelectAllClearsManualOverride() {
        // 尾部失败项远离初始视口，避免 Lazy 预取提前覆盖回归条件。
        val keys = (0 until 40).map { "candidate-$it" }
        // 生产选择规则覆盖开关、手动例外和全选。
        val selection = ImageCandidateSelection().apply { update(keys) }
        // 生产 tracker 校验稳定 key、请求版本及尝试身份。
        val tracker = ImageCandidateLoadTracker().apply { update(1, keys.associateWith { listOf(it) }) }
        compose.setContent {
            // 合成图片仅需要宿主 Context。
            val context = LocalContext.current
            // 加载器不注册网络组件，所有失败和成功均在本机生成。
            val loader = remember { ImageLoader.Builder(context).build() }
            // 前 39 项共享一张合成位图，最后一项传无法解析的数据。
            val bitmap = remember { Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888) }
            // 离开测试时释放专用加载器，不能影响其他用例。
            DisposableEffect(loader) { onDispose { loader.shutdown() } }
            MaterialTheme {
                Column {
                    ImageCandidateToolbar(
                        ImageCandidateToolbarState(false, false, selection.selectedKeys.size, keys.size,
                            selection.isAllSelected, selection.canSelectAll, selection.filterFailedEnabled, false),
                        {}, { selection.selectAll(!selection.isAllSelected) }, selection::setFilterFailed, {}, {},
                    )
                    LazyVerticalGrid(GridCells.Fixed(4), Modifier.height(240.dp).testTag("grid")) {
                        items(keys, key = { it }) { key ->
                            // 同一可见候选的选择重组不能重建模型。
                            val request = remember(key) {
                                ImageRequest.Builder(context).data(if (key == keys.last()) Any() else bitmap).build()
                            }
                            SelectableImageTile(request, key in selection.selectedKeys, loader, {},
                                { selection.setSelected(key, key !in selection.selectedKeys) },
                                Modifier.testTag(key), failureTitle = "合成失败",
                                // 与 VM 接线一致：先校验事实，再交给业务选择规则。
                                onLoadEvent = { event ->
                                    tracker.onLoadEvent(1, key, listOf(key), event)
                                    selection.updateFailures(tracker.failedKeys.toSet())
                                })
                        }
                    }
                }
            }
        }
        compose.runOnIdle { assertFalse(keys.last() in tracker.failedKeys) }
        compose.onNodeWithText("过滤失败图片").performClick().assertIsSelected()
        compose.onNodeWithTag("grid").performScrollToIndex(39)
        compose.waitUntil(10_000) { keys.last() in tracker.failedKeys }
        compose.onNodeWithText("合成失败").assertIsDisplayed()
        compose.runOnIdle { assertFalse(keys.last() in selection.selectedKeys) }
        // 使用生产缩略图的真实复选点击节点，验证手动勾选通路。
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
            and hasAnyAncestor(hasTestTag(keys.last())), useUnmergedTree = true).performClick()
        compose.runOnIdle { assertTrue(keys.last() in selection.selectedKeys) }
        compose.onNodeWithTag("grid").performScrollToIndex(0)
        compose.onNodeWithTag("grid").performScrollToIndex(39)
        compose.waitUntil(10_000) { keys.last() in tracker.failedKeys }
        compose.runOnIdle { assertTrue(keys.last() in selection.selectedKeys) }
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("过滤失败图片").assertIsSelected()
        compose.runOnIdle { assertFalse(keys.last() in selection.selectedKeys) }
    }
}
