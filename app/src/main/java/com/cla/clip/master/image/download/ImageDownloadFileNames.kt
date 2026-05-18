package com.cla.clip.master.image.download

/**
 * 图片批量下载文件名/目录名辅助方法。
 *
 * 放在图片下载领域包中，避免 Worker 和保存工具各自维护路径非法字符清理规则；最终长度和冲突规避仍交给
 * `createUniqueImageFolderName` 统一处理。
 */

/** 清理文件夹名里的非法字符，避免网页标题直接作为目录名时创建失败；最终长度由保存工具统一限制。 */
fun sanitizeImageDownloadFolderName(raw: String): String {
    return raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_").trim()
}
