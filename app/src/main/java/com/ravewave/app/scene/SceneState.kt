package com.ravewave.app.scene

data class SceneState(
    val enabledLayers: Set<VisualLayer> = setOf(VisualLayer.SPECTRUM),
    val enabledEffects: Set<PostEffect> = emptySet(),
    val fxIntensity: Float = 0.6f,
    val tileCount: Int = 3,
    val symmetrySegments: Int = 6,
    val sourceMode: SourceMode = SourceMode.MICROPHONE,
    val isFullscreen: Boolean = false,
    val isExternalVisualsEnabled: Boolean = false,
    val isCastModeEnabled: Boolean = false,
    val activePresetName: String? = null
)
