package com.ravewave.app.audio

import android.content.Context
import android.media.projection.MediaProjection
import android.net.Uri
import com.ravewave.app.analyzer.AudioAnalyzerEngine
import com.ravewave.app.scene.SourceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioSourceManager(
    context: Context,
    private val analyzerEngine: AudioAnalyzerEngine
) {
    private val appContext = context.applicationContext

    private val micSource = MicrophoneAudioSource()
    private val fileSource = FilePlaybackAudioSource(appContext)
    private var playbackCaptureSource: PlaybackCaptureAudioSource? = null

    private var activeSource: AudioSource? = null

    private var selectedFileUri: Uri? = null
    private var selectedFileName: String? = null

    private val frameConsumer = AudioFrameConsumer { samples, length, sampleRate ->
        analyzerEngine.submitPcm(samples, length, sampleRate)
    }

    private val _status = MutableStateFlow(
        AudioSourceStatus(
            activeMode = SourceMode.MICROPHONE,
            isRunning = false,
            isPlaying = false,
            selectedFileName = null,
            message = "Idle"
        )
    )
    val status: StateFlow<AudioSourceStatus> = _status.asStateFlow()

    init {
        micSource.setConsumer(frameConsumer)
        fileSource.setConsumer(frameConsumer)
    }

    fun setFile(uri: Uri, displayName: String?) {
        selectedFileUri = uri
        selectedFileName = displayName ?: uri.lastPathSegment ?: "Selected file"
        fileSource.setFile(uri)
        _status.value = _status.value.copy(
            selectedFileName = selectedFileName,
            message = "File loaded"
        )
    }

    fun switchToMicrophone() {
        runSwitch(micSource, SourceMode.MICROPHONE, "Microphone active")
    }

    fun switchToFilePlayback() {
        if (selectedFileUri == null) {
            _status.value = _status.value.copy(message = "Select an audio file first")
            return
        }
        runSwitch(fileSource, SourceMode.FILE, "File playback active")
    }

    fun switchToPlaybackCapture(mediaProjection: MediaProjection) {
        playbackCaptureSource?.release()
        playbackCaptureSource = PlaybackCaptureAudioSource(mediaProjection).also {
            it.setConsumer(frameConsumer)
        }
        runSwitch(
            playbackCaptureSource,
            SourceMode.PLAYBACK_CAPTURE,
            "Playback capture active"
        )
    }

    fun toggleFilePlayback() {
        fileSource.togglePlayback()
        _status.value = _status.value.copy(
            isPlaying = fileSource.isPlaying(),
            message = if (fileSource.isPlaying()) "Playback running" else "Playback paused"
        )
    }

    fun release() {
        try {
            activeSource?.stop()
        } catch (_: Throwable) {
        }
        micSource.release()
        fileSource.release()
        playbackCaptureSource?.release()
        playbackCaptureSource = null
    }

    private fun runSwitch(source: AudioSource?, mode: SourceMode, successMessage: String) {
        if (source == null) {
            _status.value = _status.value.copy(message = "Source unavailable")
            return
        }

        try {
            if (activeSource != source) {
                activeSource?.stop()
                activeSource = source
            }
            source.start()

            _status.value = AudioSourceStatus(
                activeMode = mode,
                isRunning = true,
                isPlaying = fileSource.isPlaying(),
                selectedFileName = selectedFileName,
                message = successMessage
            )
        } catch (t: Throwable) {
            _status.value = _status.value.copy(
                activeMode = mode,
                isRunning = false,
                message = t.message ?: "Failed to start source"
            )
        }
    }
}
