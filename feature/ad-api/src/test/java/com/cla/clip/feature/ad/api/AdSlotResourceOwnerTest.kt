package com.cla.clip.feature.ad.api

import org.junit.Assert.*
import org.junit.Test

/** 保护页面持有的 SDK 资源单例、重入与关闭后拒绝复活契约。 */
class AdSlotResourceOwnerTest {
    /** 多次绑定只创建一份资源，关闭后永远不能被迟到绑定复活。 */
    @Test fun reusesResourceAndClosesExactlyOnce() {
        // 无 Android 依赖的所有者及创建/关闭计数。
        val owner = AdSlotResourceOwner()
        var created = 0
        var closed = 0
        // 合成资源的释放回调会重入，模拟 SDK 回报 Released。
        val resource = owner.getOrCreate {
            created++
            AdSlotResource { reason ->
                assertEquals(AdReleaseReason.PageLeft, reason)
                closed++
                owner.close(reason)
            }
        }
        assertSame(resource, owner.getOrCreate { error("不得重复创建") })
        owner.close(AdReleaseReason.PageLeft)
        owner.close(AdReleaseReason.Destroyed)
        assertNull(owner.getOrCreate { error("不得复活") })
        assertEquals(1, created)
        assertEquals(1, closed)
    }
}
