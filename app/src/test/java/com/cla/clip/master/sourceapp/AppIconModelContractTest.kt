package com.cla.clip.master.sourceapp

import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.master.installedapps.InstalledAppInfo
import com.cla.clip.master.installedapps.InstalledAppPackageEntry
import com.cla.clip.master.ui.page.mine.BlockedSourceAppCandidate
import org.junit.Assert.assertTrue
import org.junit.Test

/** 防止分页或安装列表再次长期持有图像，使 Manager 淘汰无法释放离屏引用。 */
class AppIconModelContractTest {
    /** 轻量列表模型仅保存包名和文本；图像及其主色属于 UI 加载状态。 */
    @Test fun listModelsDoNotRetainIconOrColor() {
        /** 覆盖正文分页与安装选择两条长期缓存链路。 */
        val models = listOf(ClipShowEntity::class.java, InstalledAppInfo::class.java,
            InstalledAppPackageEntry::class.java, BlockedSourceAppCandidate::class.java)
        models.forEach { model ->
            /** 外部可见数据字段不能携带 Android 图像或同源颜色。 */
            val imageFields = model.declaredFields.filter { field ->
                field.type.name.startsWith("android.graphics.") ||
                    field.name in setOf("icon", "primaryColor", "sourceAppIcon", "sourceAppColor")
            }
            assertTrue("${model.simpleName} 仍持有图像状态: ${imageFields.map { it.name }}", imageFields.isEmpty())
        }
    }
}
