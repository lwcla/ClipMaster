package com.cla.clip.base.general.utils

import java.util.IdentityHashMap

/** 公开文件异常表示只保留类型/代码位置，不复制 message、suppressed 正文或绝对目录。 */
internal fun formatLogThrowable(failure: Throwable): String {
    /** 有限长度避免异常循环或超长堆栈刷满日志文件。 */
    val output = StringBuilder()
    /** 身份集合不调用异常自定义 equals。 */
    val seen = IdentityHashMap<Throwable, Boolean>()
    /** 当前层仅在格式化调用内保留。 */
    var current: Throwable? = failure
    /** cause 最多八层，正常框架包装仍保留可定位代码帧。 */
    var depth = 0
    while (current != null && depth++ < 8 && seen.put(current, true) == null) {
        output.append(current.javaClass.simpleName.take(64)).append('\n')
        // frame 只记录编译代码位置，路径只取最后一级文件名。
        for (frame in current.stackTrace.take(32)) {
            /** 文件名可能来自运行时生成帧，去掉所有目录前缀。 */
            val file = frame.fileName?.substringAfterLast('/')?.substringAfterLast('\\') ?: "Unknown"
            output.append(" at ").append(frame.className.take(128)).append('.').append(frame.methodName.take(64))
                .append('(').append(file.take(64)).append(':').append(frame.lineNumber).append(")\n")
        }
        current = current.cause
    }
    return output.toString()
}
