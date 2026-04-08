package com.cla.clip.master.ui.page.video

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cla.clip.master.BaseViewModel
import com.cla.clip.master.entity.VideoCandidate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

sealed interface ProbeState {
    data object Idle : ProbeState
    data class HiddenProbing(val sessionId: Int) : ProbeState
    data class NeedUserPlay(val sessionId: Int) : ProbeState
    data class Success(val candidate: VideoCandidate) : ProbeState
    data object Failed : ProbeState
}

@HiltViewModel
class VideoExtractVm @Inject constructor(
    @param:ApplicationContext override val appContext: Context
) : BaseViewModel(appContext) {

    var probeState by mutableStateOf<ProbeState>(ProbeState.Idle)

    var sessionId by mutableIntStateOf(0)

}