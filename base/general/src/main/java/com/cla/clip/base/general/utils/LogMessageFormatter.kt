package com.cla.clip.base.general.utils

/** 公开采集放行旧 DEBUG 日志时，去掉地址令牌；不宣称自动识别业务正文或 SDK 日志。 */
private val logAddressToken = Regex("(?i)\\b(?:https?|content|file)://[^\\s<>\"']+")

/** 保留诊断 code/类型，只移除完整网络地址、文件路径和授权 URI，不解析或访问地址。 */
internal fun formatLogMessage(message: String): String = logAddressToken.replace(message, "<地址已省略>")
