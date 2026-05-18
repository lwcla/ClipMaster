package com.cla.clip.master.image.download

/**
 * 下载内容质量校验主动过滤图片时使用的异常。
 *
 * 调用方通过该异常区分“资源下载成功但不是有效正文图片”和“网络、解码、文件写入等真实失败”，从而把过滤数量和失败数量分开统计。
 */
class FilteredImageException(message: String) : Exception(message)
