package com.cla.clip.master.ui.widget.image

/** 预览定位只保存图片身份和显示位置，不包含图片数据，不参与持久化或反射。 */
internal data class ImagePreviewPosition(
    /** 当前图片的稳定身份；追加或重排后仍优先保持此项。 */
    val key: String,
    /** 当前快照中的零基位置，仅用于 Pager 定位和一基计数。 */
    val index: Int,
)

/** 按稳定身份定位；首次目标丢失或集合为空返回 null，已打开项删除后选择最近位置。 */
internal fun resolveImagePreviewPosition(
    keys: List<String>,
    currentKey: String,
    previousIndex: Int?,
): ImagePreviewPosition? {
    if (keys.isEmpty()) return null
    // 优先保持同一张图片，不能让列表插入把旧下标解释成另一张。
    val matchedIndex = keys.indexOf(currentKey)
    if (matchedIndex >= 0) return ImagePreviewPosition(currentKey, matchedIndex)
    if (previousIndex == null) return null
    // 当前图片被删除时保留就近浏览方向，末项删除安全回退。
    val fallbackIndex = previousIndex.coerceIn(keys.indices)
    return ImagePreviewPosition(keys[fallbackIndex], fallbackIndex)
}
