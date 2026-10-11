package com.cla.clip.master.sourceapp

import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** 图标与颜色同源的不可变快照；空图标表示 UI 使用共享默认资源。 */
internal data class AppIconEntry<Icon>(
    /** 只保存小图标引用，不随 Map 失效修改已发布对象。 */
    val icon: Icon?,
    /** 真实图标的可空 ARGB 主色；主题兜底留给 UI。 */
    val primaryColor: Int?,
)

/** 纯内存 LRU；读函数可替换测试，无文件、数据库或永续后台作用域。 */
internal class AppIconCache<Icon>(
    /** 系统读取边界；失败返回空快照，取消传播。 */
    private val read: suspend (String) -> AppIconEntry<Icon>,
    /** 缓存最多保留的包名数，小图尺寸由平台读取器限制。 */
    private val maxEntries: Int = 256,
    /** 失败有效期使用单调时钟，单位毫秒。 */
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    /** 短期失败冷却，默认三十秒，不永久屏蔽重新安装应用。 */
    private val failureTtlMillis: Long = 30_000,
) {
    /** 短临界区只保护缓存和代次，不在此锁内读取系统。 */
    private val guard = Any()
    /** 按访问顺序淘汰；UI 仍可持有被淘汰的快照。 */
    private val entries = LinkedHashMap<String, Pair<AppIconEntry<Icon>, Long>>(16, 0.75f, true)
    /** 固定分片锁防止包名锁集合无限增长；同包串行，碰撞仅减少并发。 */
    private val stripes = Array(32) { Mutex() }
    /** 同时最多两项重型读取/取色，跨所有入口共用。 */
    private val permits = Semaphore(2)
    /** 失效代次保护未完成读取不会重新填入旧缓存。 */
    private var generation = 0L
    /** 可用于合成测试和低敏容量诊断的条目数。 */
    val size: Int get() = synchronized(guard) { entries.size }

    /** 缓存缺失才读取；未知身份不查询，取消不记录为负缓存。 */
    suspend fun load(packageName: String): AppIconEntry<Icon> {
        if (packageName.isBlank()) return AppIconEntry(null, null)
        /** 同包名共享固定分片锁，不阻塞主线程等待。 */
        val stripe = stripes[(packageName.hashCode() and Int.MAX_VALUE) % stripes.size]
        return stripe.withLock {
            /** 锁内检查缓存，后来的同包请求直接取得前一次结果。 */
            val cached = synchronized(guard) {
                /** 失败快照只在冷却期间有效，成功快照沿用到容量淘汰或内存清理。 */
                val entry = entries[packageName]
                if (entry != null && entry.second > nowMillis()) entry.first else null
            }
            if (cached != null) return@withLock cached
            /** 开始平台读取时的代次。 */
            val startedGeneration = synchronized(guard) { generation }
            /** 本次结果只填入当前读取方的展示对象，失败也不拖垮列表。 */
            val loaded = try {
                permits.withPermit { read(packageName) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AppIconEntry<Icon>(null, null)
            }
            // 同步系统调用可能忽略取消；返回后统一检查，禁止留下成功或失败缓存。
            currentCoroutineContext().ensureActive()
            synchronized(guard) {
                if (generation == startedGeneration) {
                    entries[packageName] = loaded to if (loaded.icon == null) nowMillis() + failureTtlMillis else Long.MAX_VALUE
                    while (entries.size > maxEntries.coerceAtLeast(1)) entries.remove(entries.keys.first())
                }
            }
            loaded
        }
    }

    /** 内存压力清空缓存，仍被 UI 引用的 Bitmap 不 recycle。 */
    fun clear() = synchronized(guard) { generation++; entries.clear() }
}
