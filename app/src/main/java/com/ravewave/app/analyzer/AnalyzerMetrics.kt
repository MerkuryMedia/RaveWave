package com.ravewave.app.analyzer

data class AnalyzerMetrics(
    val frameIndex: Long,
    val bass: Float,
    val mids: Float,
    val highs: Float,
    val energy: Float,
    val onsetPulse: Float,
    val beatPulse: Float,
    val peakHold: Float,
    val attackMod: Float,
    val decayMod: Float
)
