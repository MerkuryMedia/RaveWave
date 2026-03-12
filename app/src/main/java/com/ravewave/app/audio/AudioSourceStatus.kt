package com.ravewave.app.audio

import com.ravewave.app.scene.SourceMode

data class AudioSourceStatus(
    val activeMode: SourceMode,
    val isRunning: Boolean,
    val isPlaying: Boolean,
    val selectedFileName: String? = null,
    val message: String = "Idle"
)
