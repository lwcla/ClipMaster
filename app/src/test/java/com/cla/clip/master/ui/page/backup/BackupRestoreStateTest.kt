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
