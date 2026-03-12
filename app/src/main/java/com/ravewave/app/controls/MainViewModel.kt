package com.ravewave.app.controls

import android.app.Application
import android.media.projection.MediaProjection
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.cast.framework.CastSession
import com.ravewave.app.RaveWaveApplication
import com.ravewave.app.scene.PostEffect
import com.ravewave.app.scene.SourceMode
import com.ravewave.app.scene.VisualLayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RaveWaveApplication

    private val presetNames = MutableStateFlow(app.sceneRepository.listPresetNames())

    val uiState: StateFlow<ControlUiState> = combine(
        app.sceneRepository.state,
        app.audioSourceManager.status,
        app.castController.uiState,
        app.displaySessionManager.state,
        app.analyzerEngine.metrics
    ) { scene, source, cast, display, metrics ->
        Quintuple(scene, source, cast, display, metrics)
    }.combine(presetNames) { core, presets ->
        ControlUiState(
            scene = core.first,
            sourceStatus = core.second,
            castState = core.third,
            externalDisplayState = core.fourth,
            analyzerMetrics = core.fifth,
            presetNames = presets
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ControlUiState(
            scene = app.sceneRepository.state.value,
            sourceStatus = app.audioSourceManager.status.value,
            castState = app.castController.uiState.value,
            externalDisplayState = app.displaySessionManager.state.value,
            analyzerMetrics = app.analyzerEngine.metrics.value,
            presetNames = presetNames.value
        )
    )

    init {
        viewModelScope.launch {
            combine(app.sceneRepository.state, app.analyzerEngine.metrics) { scene, metrics ->
                scene to metrics
            }.collect { (scene, metrics) ->
                val session: CastSession? = app.castController.currentSession()
                if (scene.isCastModeEnabled && session?.isConnected == true) {
                    app.castStateSync.sendSceneState(session, scene)
                    app.castStateSync.sendMetrics(session, metrics)
                }
            }
        }
    }

    fun selectSource(mode: SourceMode) {
        app.sceneRepository.setSourceMode(mode)
        when (mode) {
            SourceMode.MICROPHONE -> app.audioSourceManager.switchToMicrophone()
            SourceMode.FILE -> app.audioSourceManager.switchToFilePlayback()
            SourceMode.PLAYBACK_CAPTURE -> Unit
        }
    }

    fun setPlaybackCaptureSource(mediaProjection: MediaProjection) {
        app.sceneRepository.setSourceMode(SourceMode.PLAYBACK_CAPTURE)
        app.audioSourceManager.switchToPlaybackCapture(mediaProjection)
    }

    fun setSelectedFile(uri: Uri, displayName: String?) {
        app.audioSourceManager.setFile(uri, displayName)
    }

    fun toggleFilePlayback() {
        app.audioSourceManager.toggleFilePlayback()
    }

    fun setLayerEnabled(layer: VisualLayer, enabled: Boolean) {
        app.sceneRepository.setLayerEnabled(layer, enabled)
    }

    fun setEffectEnabled(effect: PostEffect, enabled: Boolean) {
        app.sceneRepository.setEffectEnabled(effect, enabled)
    }

    fun setFxIntensity(progress0to100: Int) {
        app.sceneRepository.setFxIntensity(progress0to100 / 100f)
    }

    fun setTileCount(progress: Int) {
        app.sceneRepository.setTileCount(progress + 2)
    }

    fun setSymmetry(progress: Int) {
        app.sceneRepository.setSymmetrySegments(progress + 4)
    }

    fun setFullscreen(enabled: Boolean) {
        app.sceneRepository.setFullscreen(enabled)
    }

    fun setExternalVisualsEnabled(enabled: Boolean) {
        app.sceneRepository.setExternalVisualsEnabled(enabled)
    }

    fun setCastMode(enabled: Boolean) {
        app.sceneRepository.setCastModeEnabled(enabled)
    }

    fun savePreset(name: String) {
        app.sceneRepository.savePreset(name)
        presetNames.value = app.sceneRepository.listPresetNames()
    }

    fun loadPreset(name: String): Boolean {
        val loaded = app.sceneRepository.loadPreset(name)
        if (loaded) {
            presetNames.value = app.sceneRepository.listPresetNames()
        }
        return loaded
    }

    private data class Quintuple<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E
    )
}
