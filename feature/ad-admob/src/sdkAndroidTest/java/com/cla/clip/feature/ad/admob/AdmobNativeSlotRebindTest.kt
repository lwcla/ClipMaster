package com.cla.clip.feature.ad.admob

import android.widget.FrameLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cla.clip.feature.ad.api.AdSlotEventType
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 使用生产 Compose 入口与 Lazy 回收，保护缓存素材回到视口后的实际挂载，不依赖广告网络。 */
@RunWith(AndroidJUnit4::class)
class AdmobNativeSlotRebindTest {
    /** 独立测试 Activity 承载真实 AndroidView。 */
    @get:Rule val compose = createComposeRule()
    /** 本用例的合成已加载资源，只在主线程初始化和释放。 */
    private lateinit var fixture: AdmobCachedSlotFixture
    /** 生产 Lazy 状态负责真实离屏和重新组合。 */
    private lateinit var listState: LazyListState

    /** 每项用例独立预装素材，既不复用前一用例状态也不触发真实网络。 */
    @Before fun createFixture() {
        compose.runOnIdle {
            fixture = AdmobCachedSlotFixture(InstrumentationRegistry.getInstrumentation().targetContext)
        }
    }

    /** 清理真实资源，测试失败也不能遗留 View 和协程。 */
    @After fun releaseFixture() {
        if (::fixture.isInitialized) compose.runOnIdle { fixture.close() }
    }

    /** 原 View 离屏后应回到新容器，且继续使用同一素材而非请求或销毁。 */
    @Test fun cachedAdReturnsToContainerAfterLazyItemLeavesViewport() {
        compose.setContent {
            MaterialTheme {
                listState = rememberLazyListState()
                LazyColumn(state = listState, modifier = Modifier.height(320.dp)) {
                    item(key = "ad-with-body") {
                        Column {
                            Text("首段合成正文", Modifier.height(48.dp))
                            fixture.source.NativeAdSlot(
                                fixture.request.copy(canReveal = !listState.isScrollInProgress,
                                    canRevealNow = { !listState.isScrollInProgress }),
                                fixture.events::add,
                                Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    items(30, key = { "body-$it" }) { index ->
                        Text("合成正文 $index", Modifier.height(100.dp))
                    }
                }
            }
        }
        assertAdAttached("初次挂载")
        // 多次滚到远处确保首条目退出组合，也保护第二次及后续容器交接。
        repeat(3) { cycle ->
            compose.runOnIdle { listState.requestScrollToItem(20) }
            compose.runOnIdle {
                assertNull("离屏应解绑 View", fixture.nativeView.parent)
                assertSame("离屏素材仍属于本页缓存", fixture.session, fixture.owner.getOrCreate { error("缓存丢失") })
                assertEquals("离屏不销毁素材", 0, fixture.destroyCount)
            }
            compose.runOnIdle { listState.requestScrollToItem(0) }
            assertAdAttached("第 ${cycle + 1} 次离屏回到顶部")
        }
        compose.runOnIdle {
            assertEquals("回绑不得销毁缓存素材", 0, fixture.destroyCount)
            assertTrue("回绑不得创建请求或释放事件", fixture.events.isEmpty())
        }
    }

    /** 内容恢复时仍需等安全许可；许可恢复后无需额外重组即可重新挂入同一素材。 */
    @Test fun cachedAdReturnsAfterContentAndLayoutPermissionRecover() {
        // 内容资格模拟空态，不更换资源身份。
        var contentPresentable by mutableStateOf(true)
        // 布局许可模拟滚动或按压结束前暂缓重新展开。
        var canReveal by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                fixture.source.NativeAdSlot(
                    fixture.request.copy(contentPresentable = contentPresentable,
                        contentPresentableNow = { contentPresentable },
                        canReveal = canReveal, canRevealNow = { canReveal }),
                    fixture.events::add,
                    Modifier.fillMaxWidth(),
                )
            }
        }
        assertAdAttached("初次挂载")
        // 已展示时开始滚动不能收起广告。
        compose.runOnIdle { canReveal = false }
        assertAdAttached("仅布局许可暂停")
        compose.runOnIdle { contentPresentable = false }
        compose.runOnIdle { assertNull("内容消失应立即解绑", fixture.nativeView.parent) }
        compose.runOnIdle { contentPresentable = true }
        compose.runOnIdle { assertNull("恢复内容仍需等待布局许可", fixture.nativeView.parent) }
        compose.runOnIdle { canReveal = true }
        assertAdAttached("内容与布局许可恢复")
    }

    /** 新绑定接管 View 后，旧组合的迟到解绑不能移除新父节点。 */
    @Test fun staleDetachCannotRemoveAdFromCurrentContainer() {
        compose.runOnIdle {
            // 旧组合身份用于模拟迟到的清理。
            val oldBinding = Any()
            // 当前组合身份应独占素材绑定。
            val currentBinding = Any()
            // 旧外壳在素材搬走后应保持空置。
            val oldContainer = FrameLayout(fixture.nativeView.context)
            // 新外壳先接管素材，再接收旧组合的解绑事件。
            val currentContainer = FrameLayout(fixture.nativeView.context)
            fixture.session.update(oldBinding, fixture.request, fixture.events::add)
            fixture.session.bindView(oldBinding, oldContainer)
            fixture.session.update(currentBinding, fixture.request, fixture.events::add)
            fixture.session.bindView(currentBinding, currentContainer)
            fixture.session.detach(oldBinding)
            assertSame(currentContainer, fixture.nativeView.parent)
            assertEquals("View 只有一个父节点", 0, oldContainer.childCount)
            assertEquals(1, currentContainer.childCount)
            assertEquals(0, fixture.destroyCount)
        }
    }

    /** 关闭的会话是终态，迟到的更新与绑定不能复活素材或重复销毁。 */
    @Test fun closedSlotRejectsLateUpdateAndBinding() {
        compose.runOnIdle {
            // 原绑定用于模拟页面释放后到达的 AndroidView 更新。
            val binding = Any()
            // 关闭后的容器必须保持空置，不能重新挂入已销毁素材。
            val container = FrameLayout(fixture.nativeView.context)
            fixture.session.update(binding, fixture.request, fixture.events::add)
            fixture.session.bindView(binding, container)
            fixture.close()
            fixture.session.update(binding, fixture.request, fixture.events::add)
            fixture.session.bindView(binding, container)
            fixture.close()
            assertNull(fixture.nativeView.parent)
            assertEquals(0, container.childCount)
            assertEquals("素材仅销毁一次", 1, fixture.destroyCount)
            assertEquals("释放事件仅发送一次", 1, fixture.events.count { it.eventType == AdSlotEventType.Released })
        }
    }

    /** 检查真正的 View 父节点和可见区域，单有缓存对象或呈现标志不能算恢复。 */
    private fun assertAdAttached(phase: String) {
        compose.runOnIdle {
            assertNotNull("$phase：缓存广告没有挂入容器，presented=${fixture.session.presented}", fixture.nativeView.parent)
            assertTrue("$phase：广告 View 应可见", fixture.nativeView.isShown)
            assertTrue("$phase：广告 View 应有高度", fixture.nativeView.height > 0)
        }
    }
}
