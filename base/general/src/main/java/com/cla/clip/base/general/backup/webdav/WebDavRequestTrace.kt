package com.cla.clip.base.general.backup.webdav

import java.util.concurrent.atomic.AtomicLong

/** 单请求低敏关联标签；不序列化、不保存地址、凭据或请求对象。 */
internal class WebDavRequestTrace(
    /** 调用方固定操作名，未知输入降级而不原样输出。 */
    operation: String,
    /** 仅允许应用产生的短任务身份，不接受 URL 或路径。 */
    taskId: String? = null,
) {
    /** 进程内唯一请求编号，用于关联阶段和请求失败输出。 */
    private val callId = nextId.incrementAndGet()
    /** 操作名白名单阻止诊断参数成为敏感文本输出通道。 */
    private val operationCode = operation.takeIf { it in operations } ?: "unknown"
    /** 任务身份允许 UUID 和既有短 ID，非法或缺失值明确未知。 */
    private val safeTaskId = taskId?.takeIf { it.matches(taskPattern) } ?: "unknown"

    /** 只输出受控关联字段，不访问 Request/Response 或异常。 */
    fun fields(): String = "operation=$operationCode callId=$callId taskId=$safeTaskId"

    companion object {
        /** 只在进程内递增，不作为持久化业务身份。 */
        private val nextId = AtomicLong()
        /** 固定的 WebDAV 执行操作集合。 */
        private val operations = setOf("upload_file", "upload_text", "download_file", "delete_file", "directory_exists", "mkcol", "propfind")
        /** 拒绝地址、路径、控制字符与过长身份。 */
        private val taskPattern = Regex("[A-Za-z0-9_-]{1,80}")
    }
}
