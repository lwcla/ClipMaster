package com.cla.clip.base.general.backup.webdav

import java.io.IOException

/** 保留请求类别和状态，不保存 URL、响应正文或服务端异常消息；只用于失败分支及低敏原因映射。 */
internal class WebDavHttpFailure(
    /** 固定操作类别，用于区分目录创建与普通上传。 */
    val operation: String,
    /** 原始 HTTP 整数状态，不能用异常文案代替协议判断。 */
    val statusCode: Int,
) : IOException("webdav_http_failure")
