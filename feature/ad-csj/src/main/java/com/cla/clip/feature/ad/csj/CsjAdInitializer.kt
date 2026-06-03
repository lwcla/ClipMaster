package com.cla.clip.feature.ad.csj

import android.content.Context
import com.cla.clip.feature.ad.api.AdRuntimePolicy
import javax.inject.Inject
import javax.inject.Singleton

/** 穿山甲 SDK 懒初始化协调器，确保同一会话只启动一次真实初始化。 */
@Singleton
internal class CsjAdInitializer @Inject constructor(
    /** 穿山甲 SDK facade；隔离第三方类型并支持测试替换。 */
    private val sdkClient: CsjSdkClient,
) {
    /** 当前初始化状态；只保存在内存中，不持久化。 */
    private var state: InitState = InitState.NotStarted

    /**
     * 确保 SDK 已初始化。
     *
     * 调用方必须先完成隐私、主进程和配置检查；这里仅保护 SDK 调用和状态复用。
     */
    fun ensureInitialized(
        context: Context,
        config: CsjAdConfig,
        runtimePolicy: AdRuntimePolicy,
        callback: CsjInitCallback,
    ) {
        when (val currentState = state) {
            InitState.Success -> {
                callback.onSuccess()
                return
            }

            is InitState.Failed -> {
                callback.onFailure(currentState.reasonCode)
                return
            }

            is InitState.Starting -> {
                currentState.callbacks += callback
                return
            }

            InitState.NotStarted -> {
                /** 初始化回调列表；SDK 异步完成后一次性通知所有等待方。 */
                val callbacks = mutableListOf(callback)
                state = InitState.Starting(callbacks)
                startInitialization(
                    context = context,
                    config = config,
                    runtimePolicy = runtimePolicy,
                    callbacks = callbacks,
                )
            }
        }
    }

    /** 调用 SDK facade 开始初始化，并把异常转换为低敏失败。 */
    private fun startInitialization(
        context: Context,
        config: CsjAdConfig,
        runtimePolicy: AdRuntimePolicy,
        callbacks: MutableList<CsjInitCallback>,
    ) {
        runCatching {
            sdkClient.initialize(
                context = context,
                config = config,
                debugMode = runtimePolicy.debugMode,
                callback = object : CsjInitCallback {
                    /** SDK 初始化成功后通知等待者。 */
                    override fun onSuccess() {
                        state = InitState.Success
                        callbacks.toList().forEach { waitingCallback -> waitingCallback.onSuccess() }
                        callbacks.clear()
                    }

                    /** SDK 初始化失败后记住低敏原因，避免当前会话反复初始化。 */
                    override fun onFailure(reasonCode: String) {
                        /** 失败原因码；空白时回退统一初始化失败。 */
                        val normalizedReason = reasonCode.ifBlank { CsjAdReason.INIT_FAILED }
                        state = InitState.Failed(normalizedReason)
                        callbacks.toList().forEach { waitingCallback -> waitingCallback.onFailure(normalizedReason) }
                        callbacks.clear()
                    }
                },
            )
        }.getOrElse {
            state = InitState.Failed(CsjAdReason.ADAPTER_EXCEPTION)
            callbacks.toList().forEach { waitingCallback -> waitingCallback.onFailure(CsjAdReason.ADAPTER_EXCEPTION) }
            callbacks.clear()
        }
    }

    /** 初始化状态，只存在于当前进程内。 */
    private sealed class InitState {
        /** 尚未尝试初始化。 */
        data object NotStarted : InitState()

        /** 正在初始化，callbacks 保存等待结果的调用方。 */
        data class Starting(
            /** 等待 SDK 初始化完成的回调集合。 */
            val callbacks: MutableList<CsjInitCallback>,
        ) : InitState()

        /** SDK 已成功初始化。 */
        data object Success : InitState()

        /** SDK 初始化已失败，本会话不再重复尝试。 */
        data class Failed(
            /** 低敏失败原因。 */
            val reasonCode: String,
        ) : InitState()
    }
}
