package com.ravewave.app.controls

import android.app.Application
import android.media.projection.MediaProjection
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.cast.framework.CastSession
import com.ravewave.app.RaveWaveApplication
import com.ravewave.app.analyzer.AnalyzerMetrics
import com.ravewave.app.audio.AudioSourceStatus
import com.ravewave.app.cast.CastUiState
import com.ravewave.app.display.ExternalDisplayState
import com.ravewave.app.scene.ColorMode
import com.ravewave.app.scene.PostEffect
import com.ravewave.app.scene.SceneRandomizer
import com.ravewave.app.scene.SceneState
import com.ravewave.app.scene.SourceMode
import com.ravewave.app.scene.VisualLayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RaveWaveApplication

    private val presetNames = MutableStateFlow(app.sceneRepository.listPresetNames())
    private val evolveEnabled = MutableStateFlow(false)
    private var evolveJob: Job? = null
    private var evolvePulseState = SceneRandomizer.EvolvePulseState()

    val uiState: StateFlow<ControlUiState> = combine(
        app.sceneRepository.state,
        app.audioSourceManager.status,
        app.castController.uiState,
        app.displaySessionManager.state,
        app.analyzerEngine.metrics,
        evolveEnabled,
        presetNames
    ) { flows ->
        @Suppress("UNCHECKED_CAST")
        ControlUiState(
            scene = flows[0] as SceneState,
            sourceStatus = flows[1] as AudioSourceStatus,
            castState = flows[2] as CastUiState,
            externalDisplayState = flows[3] as ExternalDisplayState,
            analyzerMetrics = flows[4] as AnalyzerMetrics,
            isEvolving = flows[5] as Boolean,
            presetNames = flows[6] as List<String>
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
            presetNames = presetNames.value,
            isEvolving = evolveEnabled.value
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

    fun setSpeed(progress0to100: Int) {
        app.sceneRepository.setSpeed(progress0to100 / 100f)
    }

    fun setColorMode(mode: ColorMode) {
        app.sceneRepository.setColorMode(mode)
    }

    fun randomizeScene() {
        app.sceneRepository.updateScene { SceneRandomizer.randomizeScene(it) }
    }

    fun setEvolveEnabled(enabled: Boolean) {
        if (evolveEnabled.value == enabled) return
        evolveEnabled.update { enabled }
        evolveJob?.cancel()
        if (!enabled) return

        app.sceneRepository.updateScene { SceneRandomizer.randomizeScene(it) }
        evolvePulseState = SceneRandomizer.EvolvePulseState()
        evolveJob = viewModelScope.launch {
            while (isActive) {
                val metrics = app.analyzerEngine.metrics.value
                delay(SceneRandomizer.nextEvolveDelayMs(metrics))
                val liveMetrics = app.analyzerEngine.metrics.value
                app.sceneRepository.updateScene {
                    SceneRandomizer.evolveSceneWithAudio(it, liveMetrics, evolvePulseState)
                }
            }
        }
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
        presetNames.update { app.sceneRepository.listPresetNames() }
    }

    fun loadPreset(name: String): Boolean {
        val loaded = app.sceneRepository.loadPreset(name)
        if (loaded) {
            presetNames.update { app.sceneRepository.listPresetNames() }
        }
        return loaded
    }
}
