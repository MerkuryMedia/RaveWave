package com.ravewave.app.audio

import android.content.Context
import android.media.audiofx.Visualizer
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.ravewave.app.scene.SourceMode

class FilePlaybackAudioSource(
    context: Context
) : AudioSource {
    override val mode: SourceMode = SourceMode.FILE

    private val appContext = context.applicationContext
    private val player: ExoPlayer = ExoPlayer.Builder(appContext).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true
        )
    }

    private var consumer: AudioFrameConsumer = AudioFrameConsumer { _, _, _ -> }
    private var visualizer: Visualizer? = null
    private var mediaUri: Uri? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId > 0) {
                    attachVisualizer(audioSessionId)
                }
            }
        })
    }

    override fun setConsumer(consumer: AudioFrameConsumer) {
        this.consumer = consumer
    }

    fun setFile(uri: Uri) {
        mediaUri = uri
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    fun togglePlayback() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun isPlaying(): Boolean = player.isPlaying

    override fun start() {
        if (mediaUri == null) throw IllegalStateException("No file selected")
        if (player.playbackState == Player.STATE_IDLE) {
            player.setMediaItem(MediaItem.fromUri(mediaUri!!))
            player.prepare()
        }
        player.playWhenReady = true
        player.play()
    }

    override fun stop() {
        player.pause()
    }

    override fun release() {
        detachVisualizer()
        player.release()
    }

    private fun attachVisualizer(sessionId: Int) {
        detachVisualizer()

        val vis = Visualizer(sessionId)
        val captureRange = Visualizer.getCaptureSizeRange()
        vis.captureSize = captureRange.last().coerceAtMost(2048)
        vis.setDataCaptureListener(
            object : Visualizer.OnDataCaptureListener {
                private val floatBuffer = FloatArray(vis.captureSize)

                override fun onWaveFormDataCapture(
                    visualizer: Visualizer?,
                    waveform: ByteArray?,
                    samplingRate: Int
                ) {
                    if (waveform == null) return
                    val len = minOf(waveform.size, floatBuffer.size)
                    for (i in 0 until len) {
                        floatBuffer[i] = (waveform[i].toInt() - 128) / 128f
                    }
                    consumer.onAudioFrame(floatBuffer, len, samplingRate)
                }

                override fun onFftDataCapture(
                    visualizer: Visualizer?,
                    fft: ByteArray?,
                    samplingRate: Int
                ) = Unit
            },
            Visualizer.getMaxCaptureRate() / 2,
            true,
            false
        )
        vis.enabled = true
        visualizer = vis
    }

    private fun detachVisualizer() {
        visualizer?.apply {
            enabled = false
            release()
        }
        visualizer = null
    }
}
