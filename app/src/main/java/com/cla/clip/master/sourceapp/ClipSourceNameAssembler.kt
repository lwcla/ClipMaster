package com.cla.clip.master.sourceapp

import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.repository.sourceapp.SourceAppPresentationRepository
import com.cla.clip.base.general.utils.logW
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 装配入口只协调历史名称，不改变基础 mapper、分页查询或数据库。 */
class ClipSourceNameAssembler @Inject constructor(
    /** 来源名称读取；历史图标字段不作为展示真相。 */
    private val repository: SourceAppPresentationRepository,
) {
    /** 每个 PagingData 代次拥有名称会话，缓存复用不重复装配。 */
    internal fun newSession(): ClipSourceNameSession = ClipSourceNameSession(
        readName = { repository.find(it)?.appName },
    )

}

/** 同分页代次的轻量名称缓存；不读取图标或持有平台图片。 */
internal class ClipSourceNameSession(
    /** 可替换名称读取，null 表示没有历史来源名称。 */
    private val readName: suspend (String) -> String?,
) {
    /** 名称查询短会话串行去重，不为每条同来源记录重复查库。 */
    private val nameLock = Mutex()
    /** 名称最多保留 256 个来源，防止长列表无限积累轻量元数据。 */
    private val names = java.util.LinkedHashMap<String, String?>(16, 0.75f, true)

    /** 后台装好不可变对象；失败默认、取消传播，已返回对象保持原样。 */
    suspend fun attach(clip: ClipShowEntity): ClipShowEntity = withContext(Dispatchers.IO) {
        /** 空身份直接保留基础模型，不访问名称表。 */
        val packageName = clip.sourceAppPackage?.takeIf { it.isNotBlank() } ?: return@withContext clip
        /** 同代次名称命中也包含 null，避免缺行反复查询。 */
        val name = nameLock.withLock {
            if (!names.containsKey(packageName)) {
                names[packageName] = try {
                    readName(packageName)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logW("ClipSourceName") { "来源名称不可用 reasonCode=name_unavailable type=${error.javaClass.simpleName}" }
                    null
                }
                while (names.size > 256) names.remove(names.keys.first())
            }
            names[packageName]
        }
        // 只将历史名称附到正文，图像与主色由 UI 会话按包名取得。
        clip.copy(sourceAppName = name)
    }
}
