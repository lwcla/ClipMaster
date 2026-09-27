package com.cla.clip.master.ui.widget.media

/**
 * 单帧媒体条目，稳定身份与内容始终一起交给 Lazy 网格，避免跨版本按下标配对。
 * 仅用于模块内 UI；不参与持久化、序列化或反射，因此不需要 keep。
 */
internal data class MediaGridItemSnapshot<T>(
    /** 调用方唯一业务身份加媒体命名空间；重排和删除其他图片不会改变此值。 */
    val key: String,
    /** 当前组合读取到的媒体值；上游通过替换不可变实体更新内容，不在快照内深拷贝。 */
    val media: T,
)

/**
 * 在页面组合线程一次性捕获图片与 key；空输入返回空集合，不吞掉调用方 key 提取错误。
 * 返回集合不依赖原列表后续的追加、删除或替换，Lazy 延迟读取时仍保持数量和内容一致。
 * 每次内容变化都应重新调用，不能只按可变列表对象身份 remember，否则会漏掉同对象内更新。
 */
internal fun <T> snapshotMediaGridItems(
    // 当前已加载图片；不查询数据库、不加载媒体，也不接受并发写入的普通可变集合。
    items: List<T>,
    // 由调用方提供唯一稳定身份，沿用现有媒体命名空间以保留滚动与选择关联。
    itemKey: (T) -> Any,
): List<MediaGridItemSnapshot<T>> = items.map { media ->
    // 当前媒体与其身份在同一次遍历绑定，后续回调不再读取原集合下标。
    MediaGridItemSnapshot(key = "media:${itemKey(media)}", media = media)
}
