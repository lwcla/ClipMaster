package com.cla.clip.master.ui.widget

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cla.clip.master.ui.page.image.ImageProbeSession
import com.cla.clip.master.ui.page.image.ImageProbeState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 只用合成生命周期验证生产 Compose 接线，不读取数据库、不联网、不访问用户媒体。 */
@RunWith(AndroidJUnit4::class)
class ProbeLifecycleEffectTest {
    /** 空测试宿主提供 Compose 主线程；只允许在隔离无数据设备执行。 */
    @get:Rule val compose = createComposeRule()

    /** 暂停与移除组件必须关闭探测，恢复使用新轮次，待确认结果不被重置。 */
    @Test fun stopAndDisposeCloseProbeWhileReadySurvivesRestart() {
        // 独立控制页面可见性，不依赖设备实际按 Home。
        val owner = TestOwner()
        // 生产状态机接受与真实页面相同的生命周期回调。
        val session = ImageProbeSession()
        // 模拟导航把页面移出组合树。
        val attached = mutableStateOf(true)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (attached.value) ProbeLifecycleEffect(session::resume, session::pause)
            }
        }
        compose.runOnIdle {
            assertEquals(ImageProbeState.Idle, session.state)
            owner.registry.currentState = Lifecycle.State.STARTED
            assertTrue(session.state is ImageProbeState.Probing)
            // 后台前的身份不能接受恢复后的迟到扫描结果。
            val previous = session.sessionId
            owner.registry.currentState = Lifecycle.State.CREATED
            assertEquals(ImageProbeState.Idle, session.state)
            owner.registry.currentState = Lifecycle.State.STARTED
            assertFalse(session.accepts(previous))
            session.finish(session.sessionId, true)
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.STARTED
            assertEquals(ImageProbeState.ReadyToDownload, session.state)
            session.restart()
            attached.value = false
        }
        compose.runOnIdle { assertEquals(ImageProbeState.Idle, session.state) }
    }

    /** 测试专用生命周期拥有者，调用只发生在 Compose 主线程。 */
    private class TestOwner : LifecycleOwner {
        /** 合成页面状态，不连接应用 Activity。 */
        val registry = LifecycleRegistry(this)
        /** 供生产组件使用标准 Lifecycle API。 */
        override val lifecycle: Lifecycle get() = registry
    }
}
