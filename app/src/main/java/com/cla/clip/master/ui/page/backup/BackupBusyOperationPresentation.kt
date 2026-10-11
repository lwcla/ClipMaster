package com.cla.clip.master.ui.page.backup

/** 仅本地导出在页面内实际写文件并显示模态进度；手动 WebDAV 提交只在入队成功后显示确认。 */
internal val BackupBusyOperation.isModalProgress: Boolean
    get() = this == BackupBusyOperation.Exporting
