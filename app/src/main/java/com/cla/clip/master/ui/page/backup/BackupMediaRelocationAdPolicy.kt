package com.cla.clip.master.ui.page.backup

/** 仅有确认说明或实际结果时允许广告；纯等待、扫描和错误阶段返回 false，不读取媒体内容。 */
internal val MediaRelocationUiState.isAdEligible: Boolean
    get() = when (this) {
        is MediaRelocationUiState.ReadyToConfirm,
        is MediaRelocationUiState.PermissionRequired,
        is MediaRelocationUiState.NoWork,
        is MediaRelocationUiState.Result -> true
        MediaRelocationUiState.Idle,
        MediaRelocationUiState.Estimating,
        is MediaRelocationUiState.PermissionChecking,
        is MediaRelocationUiState.Running,
        is MediaRelocationUiState.Error -> false
    }
