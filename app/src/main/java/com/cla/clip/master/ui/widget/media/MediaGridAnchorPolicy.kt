package com.cla.clip.master.ui.widget.media

/** 四列媒体首行合并后的业务身份映射；不把广告或选择状态混入真实图片顺序。 */
internal object MediaGridAnchorPolicy {
    /** 前四张图片都指向当前首行的首图 Lazy key，其余图片仍使用自身 key；空集合返回空映射。 */
    fun lazyKeyMap(contentKeys: List<Any>): Map<Any, Any> = contentKeys.mapIndexed { index, key ->
        key to if (index < 4) contentKeys.first() else key
    }.toMap()
}
