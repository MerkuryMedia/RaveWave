package com.ravewave.app.display

data class ExternalDisplayState(
    val isAvailable: Boolean = false,
    val isActive: Boolean = false,
    val displayName: String? = null,
    val message: String = "No external display"
)
