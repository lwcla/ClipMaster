package com.cla.clip.master.sourceapp

import android.content.Context
import coil3.SingletonImageLoader
import com.cla.clip.base.general.dao.SourceAppData
import com.cla.clip.base.general.repository.sourceapp.SourceAppPresentationRepository
import com.cla.clip.base.general.utils.logW
import com.cla.clip.base.general.utils.logD
import com.cla.clip.master.ui.widget.sourceapp.SourceAppIconReader
import com.cla.clip.master.ui.widget.sourceapp.SourceAppPresentationState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** 跨页面来源状态与需求共享；所有会话/状态变更由 Main 串行执行，平台读写只在 IO。 */
@Singleton
internal class SourceAppPresentationStore(
    /** 聚合查询边界，测试可替换为合成 Flow。 */
    private val observe: (Set<String>) -> Flow<List<SourceAppData>>,
    /** 单项副作用边界；平台构造函数负责切到 IO。 */
    private val repairIcon: suspend (SourceAppData) -> Boolean,
    /** 文件清理边界，只有前台需求触发。 */
    private val cleanup: suspend (Long) -> Unit,
    /** 可替换调度作用域；所有会话变更在此作用域线程串行执行。 */
    private val scope: CoroutineScope,
    /** 可替换本机时钟，测试不依赖实际清理时刻。 */
    private val nowMillis: () -> Long,
) {
    /** 平台装配仅建立协作者，实例创建时不查询、扫描或读图。 */
    @Inject
    constructor(
        /** 来源数据库边界。 */
        repository: SourceAppPresentationRepository,
        /** 私有版本文件发布边界。 */
        publisher: SourceAppIconCachePublisher,
        /** 应用 Context 不持有 Activity。 */
        @ApplicationContext context: Context,
    ) : this(
        observe = repository::observe,
        repairIcon = { source ->
            withContext(Dispatchers.IO) {
                /** 使用现有共享 Coil，缓存不可用时才访问系统。 */
                val reader = SourceAppIconReader(context, SingletonImageLoader.get(context))
                /** 每项完成后立即条件写库，不持有整批图像。 */
                val repair = SourceAppIconRepair(
                    readCached = { item -> item.iconPath?.takeIf { it.isNotBlank() }?.let { reader.readCached(it, item.iconHash) } },
                    readSystem = reader::readSystem,
                    publish = publisher::publish,
                    commit = { expected, published -> repository.commit(expected, published.path, published.hash, published.color) },
                )
                repair.repair(source)
            }
        },
        cleanup = { now -> withContext(Dispatchers.IO) { publisher.cleanup(repository.references(), now) } },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        nowMillis = System::currentTimeMillis,
    )
    /** 包名并集独立于来源值，不因图标发射取消整轮任务。 */
    private val demands = SourceAppDemandRegistry()
    /** 当前真实活跃集合；实际变化才重启元数据查询。 */
    private var active = emptySet<String>()
    /** 同包名所有展示方使用同一个轻量状态。 */
    private val states = mutableMapOf<String, MutableStateFlow<SourceAppPresentationState>>()
    /** 尚在组合但后台暂停的行保持 Flow 身份，避免恢复后引用被淘汰的旧状态。 */
    private val retained = mutableMapOf<String, Int>()
    /** 当前活跃集合对应的唯一聚合查询任务。 */
    private var observation: Job? = null
    /** 每个包名最多一个在途修复任务。 */
    private val repairs = mutableMapOf<String, Job>()
    /** 本会话已检查的元数据版本；失败不因表级通知循环重试。 */
    private val handled = mutableMapOf<String, SourceAppData>()
    /** 展示层失败反馈去重，不因同一错误回调重复查询系统。 */
    private val decodeFailures = mutableSetOf<Pair<String, String?>>()
    /** 系统读取与压缩最多同时执行两项，防止大列表造成资源尖峰。 */
    private val permits = Semaphore(2)
    /** 延迟清理节流时间，避免每个图标写入都扫描整个目录。 */
    private var lastCleanup = 0L
    /** 最后一个前台需求离开时同时取消清理任务，不在后台继续扫描。 */
    private var cleanupJob: Job? = null

    /** 取得共享状态本身不查库；由前台需求启动查询。 */
    fun state(packageName: String): StateFlow<SourceAppPresentationState> =
        states.getOrPut(packageName) { MutableStateFlow(SourceAppPresentationState.Loading) }

    /** 组合中的行保留 Flow；同一个包名的多条记录只累加引用计数。 */
    fun retain(packageName: String) { retained[packageName] = (retained[packageName] ?: 0) + 1 }

    /** 行离开组合释放引用；空闲状态可淘汰，页面不会持有过期 Flow。 */
    fun forget(packageName: String) {
        /** 减去本行后的剩余组合引用。 */
        val remaining = (retained[packageName] ?: 1) - 1
        if (remaining > 0) retained[packageName] = remaining else retained.remove(packageName)
        prune()
    }

    /** 前台会话激活或替换包名集合，重复身份不增加查询。 */
    fun acquire(owner: Any, packages: Set<String>) {
        demands.update(owner, packages)
        updateDemand()
    }

    /** 前台会话暂停/销毁只释放自己的需求，不影响其他可见页面。 */
    fun release(owner: Any) {
        demands.remove(owner)
        updateDemand()
    }

    /** 真正的显示解码失败可请求修复，同一个版本只接受一次反馈。 */
    fun reportDecodeFailure(packageName: String) {
        /** 当前来源快照，Loading/Unavailable 不能触发系统扫描。 */
        val source = (states[packageName]?.value as? SourceAppPresentationState.Ready)?.source ?: return
        if (!decodeFailures.add(packageName to source.iconPath)) return
        handled.remove(packageName)
        startRepair(source)
    }

    /** 根据包名并集切换聚合观察，图标值更新不走这条分支。 */
    private fun updateDemand() {
        /** 来自所有前台会话的去重需求。 */
        val next = demands.packages()
        if (next == active) return
        active = next
        logD("SourceAppPresentation") { "来源活跃需求变化 count=${active.size}" }
        observation?.cancel()
        repairs.keys.filter { it !in active }.toList().forEach { packageName -> repairs[packageName]?.cancel() }
        handled.keys.retainAll(active)
        decodeFailures.removeAll { it.first !in active }
        prune()
        if (active.isEmpty()) { observation = null; cleanupJob?.cancel(); return }
        /** 短调度窗口合并快速组合的行需求，不延迟图标提交。 */
        observation = scope.launch {
            delay(16)
            try {
                /** 小集合分片控制 SQL 参数量，每片共享查询而不是逐 item 查询。 */
                val queries = next.sorted().chunked(100).map { packages -> observe(packages.toSet()) }
                combine(queries) { groups -> groups.flatMap { it }.associateBy { it.packageName } }.collect { sources ->
                    next.forEach { packageName ->
                        /** 缺失行是真实不可用；表通知查询错误由 catch 保留最近有效状态。 */
                        val source = sources[packageName]
                        states.getOrPut(packageName) { MutableStateFlow(SourceAppPresentationState.Loading) }.value =
                            if (source == null) SourceAppPresentationState.Unavailable else SourceAppPresentationState.Ready(source)
                        if (source != null) startRepair(source)
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                logW("SourceAppPresentation") { "来源元数据读取失败 reasonCode=metadata_read_failed type=${error.javaClass.simpleName}" }
            }
        }
    }

    /** 单包名修复和提交独立，成功项不等待其他慢项。 */
    private fun startRepair(source: SourceAppData) {
        if (source.packageName !in active || repairs.containsKey(source.packageName) || handled[source.packageName] == source) return
        handled[source.packageName] = source
        /** LAZY 先登记身份后启动，防止立即完成的任务留下错误在途记录。 */
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                permits.withPermit {
                    repairIcon(source)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                logW("SourceAppIconRepair") { "来源图标修复失败 reasonCode=repair_failed type=${error.javaClass.simpleName}" }
            } finally {
                // 只移除本任务身份，旧取消任务不能清掉重新进入页面后的新任务。
                if (repairs[source.packageName] === coroutineContext[Job]) {
                    repairs.remove(source.packageName)
                    /** 本项期间可能到达新版本或需求重新激活，按最新状态和检查记录决定继续。 */
                    val latest = (states[source.packageName]?.value as? SourceAppPresentationState.Ready)?.source
                    if (latest != null) startRepair(latest)
                }
            }
        }
        repairs[source.packageName] = job
        job.start()
        cleanupIfNeeded()
    }

    /** 只有前台有需求时清理孤儿/旧版本，一小时最多执行一次。 */
    private fun cleanupIfNeeded() {
        /** 当前毫秒仅作本机清理限频，不写入备份。 */
        val now = nowMillis()
        if (now - lastCleanup < 60 * 60 * 1000L) return
        lastCleanup = now
        cleanupJob = scope.launch {
            try {
                // 清理与图标读取/压缩共用总 IO 配额，不在慢修复旁再开第三项扫描。
                permits.withPermit { cleanup(now) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                logW("SourceAppIconRepair") { "来源图标清理失败 reasonCode=cleanup_failed type=${error.javaClass.simpleName}" }
            }
        }
    }

    /** 状态只保留活跃或尚在组合的包名，不积累全部历史应用。 */
    private fun prune() { states.keys.removeAll { it !in active && it !in retained } }
}
