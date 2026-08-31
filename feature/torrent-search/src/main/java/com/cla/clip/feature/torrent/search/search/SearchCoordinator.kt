package com.cla.clip.feature.torrent.search.search

import com.cla.clip.feature.torrent.search.logging.ProviderLogEvent
import com.cla.clip.feature.torrent.search.logging.ProviderLogger
import com.cla.clip.feature.torrent.search.model.contract.ProviderFailureReason
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.network.toProviderFailureReason
import com.cla.clip.feature.torrent.search.network.toProviderHttpStatusCode
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 多 Provider 搜索协调器：立即并发、逐项发射、单源隔离，并统一处理超时和取消。 */
class SearchCoordinator(
    /** 单 Provider 总超时，默认 45 秒；测试可注入更小虚拟时长。 */
    private val providerTimeoutMs: Long = DEFAULT_PROVIDER_TIMEOUT_MS,
    /** 低敏日志出口；默认空实现保证核心不依赖 Android。 */
    private val logger: ProviderLogger = ProviderLogger { },
) {
    /** 对全部已启用 Provider 执行一次搜索，返回可渐进收集的事件流。 */
    fun search(
        query: String,
        providers: List<SearchProvider>,
        safeModeEnabled: Boolean,
        maxResults: Int,
    ): Flow<SearchEvent> = channelFlow {
        /** 全局已发送结果数，跨独立 Provider 协程原子限制最大结果数量。 */
        val emittedResultCount = AtomicInteger(0)
        /** 达到结果上限的共享标记；仅停止未完成 Provider，不取消仍在收集事件的调用方。 */
        val reachedResultLimit = AtomicBoolean(false)
        /** 正数上限才允许结果；零或负数立即取消，避免无意义网络请求。 */
        val effectiveMaxResults = maxResults.coerceAtLeast(0)

        if (effectiveMaxResults == 0) {
            send(SearchEvent.Completed)
            return@channelFlow
        }

        /** Supervisor 只隔离各 Provider 失败，仍保持 collector/父 scope 对所有子任务的结构化取消。 */
        supervisorScope {
            /** 已实际进入 search 的 Provider 执行集合；快源结果先缓存，不能抢先取消尚未启动的来源。 */
            val providerExecutions = mutableListOf<ProviderExecution>()
            providers.forEach { provider ->
                /** 当前 Provider 描述符只用于稳定 ID 与安全等级，日志不含展示名或请求信息。 */
                val descriptor = provider.descriptor
                if (safeModeEnabled && descriptor.safetyLevel != ProviderSafetyLevel.SAFE) {
                    /** 安全策略跳过是可观察事件，不启动协程即可保证不会触发网络副作用。 */
                    send(SearchEvent.Skipped(descriptor.id, ProviderFailureReason.SAFE_MODE_DISABLED))
                    logger.log(
                        ProviderLogEvent(
                            providerId = descriptor.id,
                            stage = "skipped",
                            resultCount = null,
                            durationMs = null,
                            httpStatusCode = null,
                            retryable = false,
                            reasonCode = ProviderFailureReason.SAFE_MODE_DISABLED.code,
                        ),
                    )
                    return@forEach
                }

                /** 单 Provider 生命周期起点，记录毫秒仅用于性能诊断。 */
                val startTimeMs = System.currentTimeMillis()
                send(SearchEvent.Started(descriptor.id))
                logger.log(
                    ProviderLogEvent(
                        providerId = descriptor.id,
                        stage = "started",
                        resultCount = null,
                        durationMs = null,
                        httpStatusCode = null,
                        retryable = null,
                        reasonCode = null,
                    ),
                )
                /** UNDIDSPATCHED 保证当前 Provider 立即进入 search；同步快源只完成 Deferred，不会阻止后续来源启动。 */
                val resultDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(providerTimeoutMs) { provider.search(query) }
                }
                providerExecutions += ProviderExecution(
                    providerId = descriptor.id,
                    startTimeMs = startTimeMs,
                    resultDeferred = resultDeferred,
                )
            }

            /** 全部 Provider 已实际启动后再创建结果处理任务，确保快源达到上限时只取消真正运行中的来源。 */
            val processorJobs = providerExecutions.map { execution ->
                launch {
                    try {
                        /** 等待当前 Provider 的独立结果，完成顺序直接决定渐进事件顺序。 */
                        val providerResults = execution.resultDeferred.await()
                        /** 本 Provider 允许送出的结果，达到全局上限后取消所有未完成任务。 */
                        val acceptedResults = providerResults.takeRemainingResults(emittedResultCount, effectiveMaxResults)
                        if (acceptedResults.isNotEmpty()) {
                            send(SearchEvent.Results(execution.providerId, acceptedResults))
                        }
                        /** 本 Provider 结束耗时，避免记录搜索关键词和结果正文。 */
                        val durationMs = System.currentTimeMillis() - execution.startTimeMs
                        logger.log(
                            ProviderLogEvent(
                                providerId = execution.providerId,
                                stage = "completed",
                                resultCount = acceptedResults.size,
                                durationMs = durationMs,
                                httpStatusCode = null,
                                retryable = null,
                                reasonCode = null,
                            ),
                        )
                        if (emittedResultCount.get() >= effectiveMaxResults && reachedResultLimit.compareAndSet(false, true)) {
                            /** 首次达到共享上限时只取消其他执行；当前快源保留已发事件并让 Flow 正常收口。 */
                            providerExecutions
                                .filter { candidate -> candidate !== execution }
                                .forEach { candidate ->
                                    candidate.resultDeferred.cancel(CancellationException("max_results_reached"))
                                }
                        }
                    } catch (cancelled: CancellationException) {
                        /** TimeoutCancellationException 属于超时失败，普通取消必须重新抛出以传播到网络 Call。 */
                        if (cancelled is TimeoutCancellationException) {
                            emitFailure(execution.providerId, ProviderFailureReason.TIMEOUT, execution.startTimeMs)
                        } else {
                            /** 内部上限取消不产生失败事件；父/collector 取消仍向下传播且不会伪造 Completed。 */
                            if (reachedResultLimit.get()) return@launch
                            emitFailure(execution.providerId, ProviderFailureReason.CANCELLED, execution.startTimeMs)
                            throw cancelled
                        }
                    } catch (failure: Throwable) {
                        /** 非取消异常被隔离成稳定事件，绝不终止其他 Provider 子协程。 */
                        val failureReason = if (failure is ProviderParseException) {
                            ProviderFailureReason.PARSE_FAILURE
                        } else {
                            failure.toProviderFailureReason()
                        }
                        emitFailure(
                            providerId = execution.providerId,
                            reason = failureReason,
                            startTimeMs = execution.startTimeMs,
                            httpStatusCode = failure.toProviderHttpStatusCode(),
                        )
                    }
                }
            }
            /** 正常路径等待全部处理任务；collector 取消会自动取消 Deferred、处理任务及底层 OkHttp Call。 */
            processorJobs.joinAll()
        }
        send(SearchEvent.Completed)
    }

    /** 发送失败事件并记录低敏结构化诊断；异常 message 和正文永远不进入日志。 */
    private suspend fun kotlinx.coroutines.channels.ProducerScope<SearchEvent>.emitFailure(
        providerId: String,
        reason: ProviderFailureReason,
        startTimeMs: Long,
        /** 只允许传入响应状态码；网络异常、超时和取消保持为空。 */
        httpStatusCode: Int? = null,
    ) {
        /** 已耗时毫秒只用于排障性能边界，不包含可识别用户输入。 */
        val durationMs = System.currentTimeMillis() - startTimeMs
        send(SearchEvent.Failed(providerId, reason))
        logger.log(
            ProviderLogEvent(
                providerId = providerId,
                stage = "failed",
                resultCount = null,
                durationMs = durationMs,
                httpStatusCode = httpStatusCode,
                retryable = reason == ProviderFailureReason.NETWORK || reason == ProviderFailureReason.TIMEOUT,
                reasonCode = reason.code,
            ),
        )
    }

    /** 在原子上限内截取当前批次，避免两个 Provider 同时越过最大结果数。 */
    private fun List<Torrent>.takeRemainingResults(
        emittedResultCount: AtomicInteger,
        maxResults: Int,
    ): List<Torrent> {
        /** 接收当前 Provider 可安全发出的结果，保持 Provider 返回顺序。 */
        val acceptedResults = mutableListOf<Torrent>()
        forEach { torrent ->
            /** 原子递增前检查保证并发批次不能同时超过全局上限。 */
            val nextIndex = emittedResultCount.incrementAndGet()
            if (nextIndex <= maxResults) {
                acceptedResults += torrent
            }
        }
        return acceptedResults
    }

    /** 协调器默认配置入口，稳定总任务超时且避免调用方散落魔法数字。 */
    companion object {
        /** 单 Provider 默认任务上限，覆盖请求、解析与结果映射全流程。 */
        const val DEFAULT_PROVIDER_TIMEOUT_MS: Long = 45_000L
    }
}

/** 单 Provider 的结构化执行句柄；把实际搜索生命周期与渐进结果处理阶段明确分开。 */
private data class ProviderExecution(
    /** 稳定 Provider ID，仅供事件和低敏日志关联。 */
    val providerId: String,
    /** 执行开始毫秒时间，用于计算诊断耗时。 */
    val startTimeMs: Long,
    /** 已实际进入 Provider.search 的独立结果任务，取消会向下传播到网络 Call。 */
    val resultDeferred: Deferred<List<Torrent>>,
)

/** 搜索过程的渐进事件；UI 后续只消费此稳定事件而不感知 Provider 内部异常。 */
sealed interface SearchEvent {
    /** 某 Provider 已启动独立任务。 */
    data class Started(
        /** 稳定 Provider ID。 */
        val providerId: String,
    ) : SearchEvent

    /** 某 Provider 产生的一批结果。 */
    data class Results(
        /** 稳定 Provider ID。 */
        val providerId: String,
        /** 已受 maxResults 限制的领域结果，不得写入日志。 */
        val torrents: List<Torrent>,
    ) : SearchEvent

    /** 某 Provider 的隔离失败。 */
    data class Failed(
        /** 稳定 Provider ID。 */
        val providerId: String,
        /** 可跨层显示与统计的稳定失败原因。 */
        val reason: ProviderFailureReason,
    ) : SearchEvent

    /** 因安全模式未启动的 Provider。 */
    data class Skipped(
        /** 稳定 Provider ID。 */
        val providerId: String,
        /** 当前只能为 safe_mode_disabled 的稳定原因。 */
        val reason: ProviderFailureReason,
    ) : SearchEvent

    /** 本次事件流正常结束；取消时 Flow 直接取消而不伪造完成。 */
    data object Completed : SearchEvent
}
