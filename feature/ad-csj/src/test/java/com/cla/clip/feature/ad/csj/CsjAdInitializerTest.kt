package com.cla.clip.feature.ad.csj

import android.content.Context
import android.content.ContextWrapper
import com.cla.clip.feature.ad.api.AdRuntimePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 穿山甲初始化协调器测试，覆盖单次初始化、失败复用和异常兜底。 */
class CsjAdInitializerTest {
    /** 默认测试配置；初始化协调器本身不校验广告位 ID。 */
    private val defaultConfig = CsjAdConfig(
        appId = "test-app-id",
        detailNativeAdSlotId = "test-slot-id",
        useTestAdSlot = false,
        sdkDependencyEnabled = true,
        sdkVersion = "test-sdk",
        requiredPrivacyPolicyVersion = "",
    )

    /** 默认运行时策略；只用于传递 debugMode。 */
    private val defaultRuntimePolicy = AdRuntimePolicy(adsGlobalEnabled = true)

    /** 初始化成功后，同一会话后续调用不应再次触发 SDK 初始化。 */
    @Test
    fun successfulInitializationRunsSdkOnlyOnce() {
        /** 假 SDK；收到初始化后立即成功。 */
        val sdkClient = FakeCsjSdkClient { callback -> callback.onSuccess() }
        /** 被测初始化器；持有当前会话状态。 */
        val initializer = CsjAdInitializer(sdkClient)
        /** 成功回调次数；用于确认两次调用都能拿到成功结果。 */
        var successCount = 0

        initializer.ensureInitialized(FakeContext, defaultConfig, defaultRuntimePolicy, countingCallback { successCount += 1 })
        initializer.ensureInitialized(FakeContext, defaultConfig, defaultRuntimePolicy, countingCallback { successCount += 1 })

        assertEquals(1, sdkClient.initializeCount)
        assertEquals(2, successCount)
    }

    /** 初始化失败后，同一会话直接复用失败原因，不反复启动 SDK。 */
    @Test
    fun failedInitializationIsRememberedForSession() {
        /** 假 SDK；收到初始化后返回稳定失败原因。 */
        val sdkClient = FakeCsjSdkClient { callback -> callback.onFailure(CsjAdReason.INIT_FAILED) }
        /** 被测初始化器；失败状态只存在内存会话中。 */
        val initializer = CsjAdInitializer(sdkClient)
        /** 收集到的失败原因；用于确认失败原因稳定。 */
        val failures = mutableListOf<String>()

        initializer.ensureInitialized(FakeContext, defaultConfig, defaultRuntimePolicy, failureCallback(failures))
        initializer.ensureInitialized(FakeContext, defaultConfig, defaultRuntimePolicy, failureCallback(failures))

        assertEquals(1, sdkClient.initializeCount)
        assertEquals(listOf(CsjAdReason.INIT_FAILED, CsjAdReason.INIT_FAILED), failures)
    }

    /** SDK facade 抛异常时应转为 adapter 异常原因，不向页面抛出。 */
    @Test
    fun sdkExceptionIsConvertedToFailureReason() {
        /** 假 SDK；模拟第三方 SDK Java/Kotlin 层异常。 */
        val sdkClient = object : FakeCsjSdkClient({}) {
            /** 初始化时直接抛出异常。 */
            override fun initialize(context: Context, config: CsjAdConfig, debugMode: Boolean, callback: CsjInitCallback) {
                initializeCount += 1
                throw IllegalStateException("boom")
            }
        }
        /** 被测初始化器；应捕获异常并返回低敏 reasonCode。 */
        val initializer = CsjAdInitializer(sdkClient)
        /** 收集到的失败原因；不得包含异常原文。 */
        val failures = mutableListOf<String>()

        initializer.ensureInitialized(FakeContext, defaultConfig, defaultRuntimePolicy, failureCallback(failures))

        assertEquals(1, sdkClient.initializeCount)
        assertEquals(listOf(CsjAdReason.ADAPTER_EXCEPTION), failures)
    }

    /** 正在初始化时，后续调用应挂到同一次 SDK 初始化结果上。 */
    @Test
    fun concurrentRequestsShareSingleInitialization() {
        /** 暂存 SDK 回调；测试手动触发成功。 */
        var pendingCallback: CsjInitCallback? = null
        /** 假 SDK；第一次初始化只保存回调，不立即完成。 */
        val sdkClient = FakeCsjSdkClient { callback -> pendingCallback = callback }
        /** 被测初始化器；第二个请求应加入等待列表。 */
        val initializer = CsjAdInitializer(sdkClient)
        /** 成功回调次数；SDK 完成后两个等待者都应收到通知。 */
        var successCount = 0

        initializer.ensureInitialized(FakeContext, defaultConfig, defaultRuntimePolicy, countingCallback { successCount += 1 })
        initializer.ensureInitialized(FakeContext, defaultConfig, defaultRuntimePolicy, countingCallback { successCount += 1 })
        pendingCallback?.onSuccess()

        assertEquals(1, sdkClient.initializeCount)
        assertEquals(2, successCount)
        assertTrue(pendingCallback != null)
    }

    /** 创建只关心成功次数的初始化回调。 */
    private fun countingCallback(
        /** 成功时执行的测试动作。 */
        onSuccess: () -> Unit,
    ): CsjInitCallback {
        return object : CsjInitCallback {
            /** 初始化成功时执行调用方传入动作。 */
            override fun onSuccess() = onSuccess()

            /** 本测试不期望失败，失败时抛出断言错误。 */
            override fun onFailure(reasonCode: String) {
                throw AssertionError("unexpected failure: $reasonCode")
            }
        }
    }

    /** 创建只收集失败原因的初始化回调。 */
    private fun failureCallback(
        /** 收集低敏失败 reasonCode 的列表。 */
        failures: MutableList<String>,
    ): CsjInitCallback {
        return object : CsjInitCallback {
            /** 本测试不期望成功，成功时抛出断言错误。 */
            override fun onSuccess() {
                throw AssertionError("unexpected success")
            }

            /** 初始化失败时记录低敏原因。 */
            override fun onFailure(reasonCode: String) {
                failures += reasonCode
            }
        }
    }
}

/** 初始化测试用 SDK facade，只实现初始化路径。 */
private open class FakeCsjSdkClient(
    /** 初始化行为，由每个测试指定成功、失败或挂起。 */
    private val initBehavior: (CsjInitCallback) -> Unit,
) : CsjSdkClient {
    /** 初始化调用次数；用于验证同一会话只启动一次 SDK。 */
    open var initializeCount: Int = 0

    /** 测试 SDK 始终可用。 */
    override val isSdkAvailable: Boolean = true

    /** 测试 SDK 版本号。 */
    override val sdkVersion: String = "test-sdk"

    /** 执行测试指定的初始化行为。 */
    override fun initialize(context: Context, config: CsjAdConfig, debugMode: Boolean, callback: CsjInitCallback) {
        initializeCount += 1
        initBehavior(callback)
    }

    /** 初始化测试不加载广告。 */
    override fun loadDetailNativeAd(
        context: Context,
        config: CsjAdConfig,
        widthDp: Float,
        heightDp: Float,
        callback: CsjNativeAdLoadCallback,
    ): CsjNativeAdHandle? {
        throw UnsupportedOperationException("loadDetailNativeAd is not used in initializer tests")
    }
}

/** 单元测试只传递 Context 类型占位，不触发 Android Framework 调用。 */
private val FakeContext: Context = ContextWrapper(null)
