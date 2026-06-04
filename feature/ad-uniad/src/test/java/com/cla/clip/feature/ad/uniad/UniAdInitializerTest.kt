package com.cla.clip.feature.ad.uniad

import android.content.Context
import android.content.ContextWrapper
import com.cla.clip.feature.ad.api.AdRuntimePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/** uni-ad 初始化器测试，覆盖同会话幂等、失败复用和异常兜底。 */
class UniAdInitializerTest {
    /** 同一会话初始化成功后不会重复调用 SDK。 */
    @Test
    fun successfulInitializationRunsOnlyOnce() {
        /** 当前假 SDK；默认同步成功。 */
        val sdkClient = FakeUniAdSdkClient()
        /** 当前初始化器；持有会话级初始化状态。 */
        val initializer = UniAdInitializer(sdkClient)
        /** 当前成功回调次数；用于确认两次 ensure 都成功返回。 */
        var successCount = 0

        initializer.ensureInitialized(testContext(), defaultConfig, defaultPolicy, successCallback { successCount += 1 })
        initializer.ensureInitialized(testContext(), defaultConfig, defaultPolicy, successCallback { successCount += 1 })

        assertEquals(1, sdkClient.initializeCount)
        assertEquals(2, successCount)
    }

    /** 初始化失败后复用失败原因，不在同一会话反复初始化。 */
    @Test
    fun failedInitializationIsRemembered() {
        /** 当前假 SDK；同步返回初始化失败。 */
        val sdkClient = FakeUniAdSdkClient(failureReason = UniAdReason.INIT_FAILED)
        /** 当前初始化器；失败状态应被记住。 */
        val initializer = UniAdInitializer(sdkClient)
        /** 当前失败原因列表；用于确认后续调用复用同一 reasonCode。 */
        val failureReasons = mutableListOf<String>()

        initializer.ensureInitialized(testContext(), defaultConfig, defaultPolicy, failureCallback(failureReasons::add))
        initializer.ensureInitialized(testContext(), defaultConfig, defaultPolicy, failureCallback(failureReasons::add))

        assertEquals(1, sdkClient.initializeCount)
        assertEquals(listOf(UniAdReason.INIT_FAILED, UniAdReason.INIT_FAILED), failureReasons)
    }

    /** SDK 抛异常时转换为 adapter 异常 reasonCode。 */
    @Test
    fun sdkExceptionIsConvertedToAdapterException() {
        /** 当前假 SDK；初始化时抛出异常。 */
        val sdkClient = FakeUniAdSdkClient(throwOnInitialize = true)
        /** 当前初始化器；异常不能抛给页面。 */
        val initializer = UniAdInitializer(sdkClient)
        /** 当前失败原因；用于确认异常被转成低敏 reasonCode。 */
        val failureReasons = mutableListOf<String>()

        initializer.ensureInitialized(testContext(), defaultConfig, defaultPolicy, failureCallback(failureReasons::add))

        assertEquals(listOf(UniAdReason.ADAPTER_EXCEPTION), failureReasons)
    }

    /** 创建成功回调；测试中只关心 onSuccess 行为。 */
    private fun successCallback(onSuccess: () -> Unit): UniAdInitCallback {
        return object : UniAdInitCallback {
            /** 初始化成功时执行测试断言计数。 */
            override fun onSuccess() = onSuccess()

            /** 初始化失败不应在成功路径触发。 */
            override fun onFailure(reasonCode: String) = Unit
        }
    }

    /** 创建失败回调；测试中只关心 onFailure reasonCode。 */
    private fun failureCallback(onFailure: (String) -> Unit): UniAdInitCallback {
        return object : UniAdInitCallback {
            /** 初始化成功不应在失败路径触发。 */
            override fun onSuccess() = Unit

            /** 初始化失败时记录低敏 reasonCode。 */
            override fun onFailure(reasonCode: String) = onFailure(reasonCode)
        }
    }

    /** 创建测试用 Context；假 SDK 不读取系统服务，因此使用空 base 的 ContextWrapper 保持纯 JVM 测试。 */
    private fun testContext(): Context {
        return ContextWrapper(null)
    }

    /** 假 uni-ad SDK facade；只覆盖初始化路径，避免单元测试依赖真实 SDK。 */
    private class FakeUniAdSdkClient(
        /** 需要同步返回的失败原因；为空表示初始化成功。 */
        private val failureReason: String? = null,
        /** 是否在初始化时抛异常；用于测试兜底。 */
        private val throwOnInitialize: Boolean = false,
    ) : UniAdSdkClient {
        /** 初始化调用次数；用于验证同会话只初始化一次。 */
        var initializeCount = 0

        /** 假 SDK 始终可用；配置可用性由其它测试覆盖。 */
        override val isSdkAvailable: Boolean = true

        /** 假 SDK 版本；只用于满足接口。 */
        override val sdkVersion: String = "fake"

        /** 模拟 SDK 初始化。 */
        override fun initialize(context: Context, config: UniAdConfig, debugMode: Boolean, callback: UniAdInitCallback) {
            initializeCount += 1
            if (throwOnInitialize) {
                throw IllegalStateException("fake")
            }
            if (failureReason == null) {
                callback.onSuccess()
            } else {
                callback.onFailure(failureReason)
            }
        }

        /** 初始化测试不触发广告加载。 */
        override fun loadDetailFeedAd(
            activity: android.app.Activity,
            requestSpec: UniAdFeedRequestSpec,
            callback: UniAdFeedAdLoadCallback,
        ): UniAdFeedLoaderHandle? = null
    }

    private companion object {
        /** 默认有效配置；初始化器本身不重复验证配置可用性。 */
        private val defaultConfig = UniAdConfig(
            appId = "123456",
            unionId = "654321",
            detailNativeAdpid = "1000000001",
            useTestAdpid = false,
            sdkDependencyEnabled = true,
            sdkVersion = "5.5.2.0606",
            requiredPrivacyPolicyVersion = "",
        )

        /** 默认运行时策略；初始化器只使用 debugMode。 */
        private val defaultPolicy = AdRuntimePolicy(adsGlobalEnabled = true, debugMode = true)
    }
}
