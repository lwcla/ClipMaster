package com.cla.clip.master.ui.page.mine

/** 来源过滤列表的单一展示形态，仅用于 UI，不参与保存、扫描或外部协议。 */
internal enum class ClipSourceBlockListContentState {
    /** 有可见行，刷新或失败仍保留原列表。 */
    Content,
    /** 初始、扫描中或候选映射未就绪，不能宣告无结果。 */
    Loading,
    /** 成功且候选快照就绪后，当前搜索/范围没有可见结果。 */
    Empty,
    /** 没有可见行且读取失败，需要明确提供重试。 */
    Failed,
}

/**
 * 归约列表展示形态；输入数量来自候选及当前过滤结果，均非负，不用于保存或扫描控制。
 * 摘要必须沿用扫描快照中的同一对象，不能只按数量或时间猜测映射已完成。
 * candidateCount 额外校验一对一映射边界，失败不表示空结果。
 */
internal fun resolveClipSourceBlockListContentState(
    // 会话读取状态区分尚未成功、扫描中、成功及失败。
    state: InstalledAppListState,
    // 未按当前关键词/范围裁剪的候选数量，用于确认后台映射已就绪。
    candidateCount: Int,
    // 当前真正可展示行数，有旧内容时优先保留列表。
    visibleCandidateCount: Int,
    // 候选携带的成功摘要身份，不能复制摘要或只按字段值比较。
    candidateLoadSummary: InstalledAppLoadSummary?,
): ClipSourceBlockListContentState {
    return when {
        // 有旧行时保留内容，刷新及失败信息由页面已有指示区域补充。
        visibleCandidateCount > 0 -> ClipSourceBlockListContentState.Content
        // 空列表正在读取时不能展示无结果，包括成功空快照后的再次刷新。
        state.loading -> ClipSourceBlockListContentState.Loading
        // 失败不能等同于空结果，且优先于首次成功标记，避免首读失败永久转圈。
        state.unavailableReasonCode != null -> ClipSourceBlockListContentState.Failed
        // 首帧或首次扫描取消后，等待生命周期入口按需扫描，不伪造业务 loading。
        !state.everLoaded || state.lastLoadSummary == null -> ClipSourceBlockListContentState.Loading
        // 候选必须来自当前成功扫描，即使新旧快照数量和时间完全相同也要等待映射。
        candidateLoadSummary !== state.lastLoadSummary -> ClipSourceBlockListContentState.Loading
        // 一对一候选数量应与成功快照一致，未就绪时不能宣告无结果。
        candidateCount != state.loadedAppCount -> ClipSourceBlockListContentState.Loading
        else -> ClipSourceBlockListContentState.Empty
    }
}
