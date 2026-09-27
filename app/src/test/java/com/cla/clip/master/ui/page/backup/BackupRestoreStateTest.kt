package com.cla.clip.master.ui.page.backup

import com.cla.clip.master.media.MediaRelocationEstimate
import com.cla.clip.master.media.MediaRelocationPreparation
import com.cla.clip.master.media.MediaRelocationProgress
import com.cla.clip.master.media.MediaRelocationReport
import com.cla.clip.master.media.MediaRelocationStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreStateTest {
    /** 确认、权限说明和结果具有真实业务内容，允许独立媒体关联广告位请求。 */
    @Test
    fun mediaRelocationAdsAllowConfirmationAndResults() {
        /** 复用既有合成预估和结果，不引入真实媒体或备份数据。 */
        val states = listOf(
            MediaRelocationUiState.ReadyToConfirm(dummyPreparation()),
            MediaRelocationUiState.PermissionRequired(dummyPreparation()),
            MediaRelocationUiState.NoWork(dummyPreparation()),
            MediaRelocationUiState.Result(dummyEstimate(), MediaRelocationReport()),
        )
        // 每个业务阶段分别断言，错误信息只使用稳定状态 code。
        states.forEach { state -> assertTrue(state.backLogCode, state.isAdEligible) }
    }

    /** 纯预估、扫描、权限复查与失败页不请求广告，避免广告抢占业务等待区域。 */
    @Test
    fun mediaRelocationAdsRejectTransientAndErrorStates() {
        /** 覆盖全部没有展示资格的状态，不依赖真实时钟、权限或文件。 */
        val states = listOf(
            MediaRelocationUiState.Idle,
            MediaRelocationUiState.Estimating,
            MediaRelocationUiState.PermissionChecking(dummyPreparation()),
            MediaRelocationUiState.Running(dummyProgress()),
            MediaRelocationUiState.Error("synthetic_failure"),
        )
        // 状态阶段的变化只决定资格，不应读取媒体数量或路径。
        states.forEach { state -> assertFalse(state.backLogCode, state.isAdEligible) }
    }

    @Test
    fun mediaRelocationSummaryTypeShouldCloseRestoreFlowOnDoneOnlyForPositiveTerminalStates() {
        assertTrue(MediaRelocationSummaryType.Completed.shouldCloseRestoreFlowOnDone)
        assertTrue(MediaRelocationSummaryType.NoWork.shouldCloseRestoreFlowOnDone)

        assertFalse(MediaRelocationSummaryType.PermissionDenied.shouldCloseRestoreFlowOnDone)
        assertFalse(MediaRelocationSummaryType.Failed.shouldCloseRestoreFlowOnDone)
        assertFalse(MediaRelocationSummaryType.Interrupted.shouldCloseRestoreFlowOnDone)
    }

    @Test
    fun mediaRelocationUiStateShouldCloseRestoreFlowOnDoneOnlyForPositiveTerminalStates() {
        val states = listOf(
            MediaRelocationUiState.Idle to false,
            MediaRelocationUiState.Estimating to false,
            MediaRelocationUiState.NoWork(dummyPreparation()) to true,
            MediaRelocationUiState.ReadyToConfirm(dummyPreparation()) to false,
            MediaRelocationUiState.PermissionRequired(dummyPreparation()) to false,
            MediaRelocationUiState.PermissionChecking(dummyPreparation()) to false,
            MediaRelocationUiState.Running(dummyProgress()) to false,
            MediaRelocationUiState.Result(dummyEstimate(), MediaRelocationReport()) to true,
            MediaRelocationUiState.Error("failed") to false,
        )

        states.forEach { (state, expected) ->
            assertEquals(state.backLogCode, expected, state.shouldCloseRestoreFlowAfterDone)
        }
    }

    @Test
    fun mediaRelocationUiStateBackLogCodeIsStable() {
        val states = listOf(
            MediaRelocationUiState.Idle to "idle",
            MediaRelocationUiState.Estimating to "estimating",
            MediaRelocationUiState.NoWork(dummyPreparation()) to "no_work",
            MediaRelocationUiState.ReadyToConfirm(dummyPreparation()) to "ready_to_confirm",
            MediaRelocationUiState.PermissionRequired(dummyPreparation()) to "permission_required",
            MediaRelocationUiState.PermissionChecking(dummyPreparation()) to "permission_checking",
            MediaRelocationUiState.Running(dummyProgress()) to "running",
            MediaRelocationUiState.Result(dummyEstimate(), MediaRelocationReport()) to "result",
            MediaRelocationUiState.Error("failed") to "error",
        )

        states.forEach { (state, expected) ->
            assertEquals(expected, state.backLogCode)
        }
    }

    private fun dummyPreparation(): MediaRelocationPreparation {
        return MediaRelocationPreparation(
            estimate = dummyEstimate(),
            needsImageScan = false,
            needsVideoScan = false,
            needsImagePermission = false,
            needsVideoPermission = false,
            requiredPermissions = emptyList(),
            existingReadableVideoCount = 0,
            existingReadableImageCount = 0,
            permissionRequiredVideoCount = 0,
            permissionRequiredImageItemCount = 0,
        )
    }

    private fun dummyProgress(): MediaRelocationProgress {
        return MediaRelocationProgress(
            stage = MediaRelocationStage.VerifyingExisting,
            processedVideos = 0,
            totalVideos = 0,
            processedImageBatches = 0,
            totalImageBatches = 0,
            processedImageItems = 0,
            totalImageItems = 0,
            relocatedCount = 0,
            report = MediaRelocationReport(),
        )
    }

    private fun dummyEstimate(): MediaRelocationEstimate {
        return MediaRelocationEstimate(
            videoCount = 0,
            imageBatchCount = 0,
            imageItemCount = 0,
        )
    }
}
