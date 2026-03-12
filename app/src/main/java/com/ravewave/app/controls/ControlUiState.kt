package com.ravewave.app.controls

import com.ravewave.app.analyzer.AnalyzerMetrics
import com.ravewave.app.audio.AudioSourceStatus
import com.ravewave.app.cast.CastUiState
import com.ravewave.app.display.ExternalDisplayState
import com.ravewave.app.scene.SceneState

data class ControlUiState(
    val scene: SceneState,
    val sourceStatus: AudioSourceStatus,
    val castState: CastUiState,
    val externalDisplayState: ExternalDisplayState,
    val analyzerMetrics: AnalyzerMetrics,
    val presetNames: List<String>,
    val isEvolving: Boolean
)
