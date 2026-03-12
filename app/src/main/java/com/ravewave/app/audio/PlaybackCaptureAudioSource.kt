package com.ravewave.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import com.ravewave.app.scene.SourceMode
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class PlaybackCaptureAudioSource(
    private val mediaProjection: MediaProjection
) : AudioSource {
    override val mode: SourceMode = SourceMode.PLAYBACK_CAPTURE

    private var consumer: AudioFrameConsumer = AudioFrameConsumer { _, _, _ -> }
    private var audioRecord: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private var workerThread: Thread? = null

    override fun setConsumer(consumer: AudioFrameConsumer) {
        this.consumer = consumer
    }

    override fun start() {
        if (running.get()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw UnsupportedOperationException("Playback capture requires Android 10+")
        }

        val sampleRate = 48000
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes((minBuffer * 2).coerceAtLeast(4096))
            .setAudioPlaybackCaptureConfig(config)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Playback-capture AudioRecord failed to initialize")
        }

        audioRecord = record
        running.set(true)
        record.startRecording()

        workerThread = thread(name = "PlaybackCaptureSource") {
            val shortBuffer = ShortArray(1024)
            val floatBuffer = FloatArray(shortBuffer.size)
            while (running.get()) {
                val read = record.read(shortBuffer, 0, shortBuffer.size)
                if (read <= 0) continue
                for (i in 0 until read) {
                    floatBuffer[i] = shortBuffer[i] / 32768f
                }
                consumer.onAudioFrame(floatBuffer, read, sampleRate)
            }
        }
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        audioRecord?.let {
            try {
                it.stop()
            } catch (_: Throwable) {
            }
        }
        workerThread?.join(250)
        workerThread = null
        audioRecord?.release()
        audioRecord = null
    }

    override fun release() {
        stop()
        try {
            mediaProjection.stop()
        } catch (_: Throwable) {
        }
    }
}
