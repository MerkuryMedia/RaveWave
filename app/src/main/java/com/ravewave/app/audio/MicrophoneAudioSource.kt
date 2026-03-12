package com.ravewave.app.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.ravewave.app.scene.SourceMode
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MicrophoneAudioSource : AudioSource {
    override val mode: SourceMode = SourceMode.MICROPHONE

    private var consumer: AudioFrameConsumer = AudioFrameConsumer { _, _, _ -> }
    private var audioRecord: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private var workerThread: Thread? = null

    override fun setConsumer(consumer: AudioFrameConsumer) {
        this.consumer = consumer
    }

    override fun start() {
        if (running.get()) return

        val sampleRate = 48000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = (minBuffer * 2).coerceAtLeast(4096)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.UNPROCESSED,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (_: Throwable) {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Microphone AudioRecord failed to initialize")
        }

        audioRecord = record
        running.set(true)
        record.startRecording()

        workerThread = thread(name = "MicAudioSource") {
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
    }
}
