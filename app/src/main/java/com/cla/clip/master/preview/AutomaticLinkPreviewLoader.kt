package com.cla.clip.master.preview

import com.cla.clip.base.general.config.privacy.PrivacySettingsRepository
import com.cla.clip.base.general.utils.logW
import com.cla.clip.master.utils.LinkMeta
import com.cla.clip.master.utils.LinkMetaParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import javax.inject.Inject
import javax.inject.Singleton

/** 自动预览唯一入口：设置读取失败或关闭只跳过预览，不影响此前已保存的文本。 */
@Singleton
internal class AutomaticLinkPreviewLoader @Inject constructor(
    /** 独立的本机联网许可，不查询任何系统权限。 */ private val settings: PrivacySettingsRepository,
    /** 保留既有两路 HTML 解析能力，不复制解析规则。 */ private val parser: LinkMetaParser,
) {
    /** 关闭返回 null；取消调用方时继续传播，其他错误低频降级且不记录链接。 */
    suspend fun load(url: String): LinkMeta? = try {
        withPreviewPermission(settings.previewEnabled.catch { failure ->
            if (failure is CancellationException) throw failure
            logW("LinkPreview") { "预览设置读取失败 reasonCode=settings_read_failed type=${failure.javaClass.simpleName}" }
            emit(false)
        }) { parser.parse(url) }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
        logW("LinkPreview") { "自动预览失败 reasonCode=preview_failed type=${failure.javaClass.simpleName}" }
        null
    }
}
