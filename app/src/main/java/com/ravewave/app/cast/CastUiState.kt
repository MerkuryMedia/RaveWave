package com.ravewave.app.cast

data class CastUiState(
    val isAvailable: Boolean = false,
    val isConnected: Boolean = false,
    val routeName: String? = null,
    val message: String = "Cast idle"
)
