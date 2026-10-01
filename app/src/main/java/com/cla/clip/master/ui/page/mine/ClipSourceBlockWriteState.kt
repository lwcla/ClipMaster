package com.cla.clip.master.ui.page.mine

/** 页面内部提交失败分类，不参与存储、反射或备份。 */
internal enum class ClipSourceBlockWriteFailure { Capacity, InvalidPackage, Storage }

/** 单项即时提交及返回等待状态，持久名单仍是唯一的过滤事实来源。 */
internal data class ClipSourceBlockWriteState(
    /** 当前已接受但尚未结束的包名，用于逐行禁用重复点击。 */
    val pendingPackages: Set<String> = emptySet(),
    /** 最近操作失败，供提交结果和返回行为判断，不存储异常正文。 */
    val lastFailure: ClipSourceBlockWriteFailure? = null,
    /** 用户已经要求返回，提交期间不继续接受新操作。 */
    val backRequested: Boolean = false,
    /** 等待提交成功后自动返回；页面销毁后事件自然结束。 */
    val navigateBack: Boolean = false,
)
