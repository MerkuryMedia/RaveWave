package com.ravewave.app.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DisplaySessionManager(context: Context) {
    private val appContext = context.applicationContext
    private val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private var presentation: VisualizerPresentation? = null

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshState("Display connected")
        override fun onDisplayRemoved(displayId: Int) {
            if (presentation?.display?.displayId == displayId) {
                dismissPresentation()
            }
            refreshState("Display removed")
        }

        override fun onDisplayChanged(displayId: Int) = refreshState("Display changed")
    }

    private val _state = MutableStateFlow(ExternalDisplayState())
    val state: StateFlow<ExternalDisplayState> = _state.asStateFlow()

    fun onStart() {
        displayManager.registerDisplayListener(listener, null)
        refreshState("Display manager ready")
    }

    fun onStop() {
        displayManager.unregisterDisplayListener(listener)
    }

    fun setExternalVisualsEnabled(
        enabled: Boolean,
        hostContext: Context,
        contentFactory: (Context) -> android.view.View
    ): Boolean {
        if (!enabled) {
            dismissPresentation()
            refreshState("External visuals disabled")
            return true
        }

        val display = findPresentationDisplay()
        if (display == null) {
            refreshState("No presentation display available")
            return false
        }

        if (presentation?.isShowing == true && presentation?.display?.displayId == display.displayId) {
            refreshState("External visuals active")
            return true
        }

        dismissPresentation()
        presentation = VisualizerPresentation(hostContext, display, contentFactory).also { it.show() }
        refreshState("External visuals active")
        return true
    }

    private fun dismissPresentation() {
        presentation?.dismiss()
        presentation = null
    }

    private fun findPresentationDisplay(): Display? {
        return displayManager.displays.firstOrNull {
            it.displayId != Display.DEFAULT_DISPLAY &&
                it.flags and Display.FLAG_PRESENTATION != 0
        } ?: displayManager.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }

    private fun refreshState(message: String) {
        val display = findPresentationDisplay()
        _state.value = ExternalDisplayState(
            isAvailable = display != null,
            isActive = presentation?.isShowing == true,
            displayName = display?.name,
            message = message
        )
    }
}
