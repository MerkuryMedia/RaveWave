package com.ravewave.app.audio

import com.ravewave.app.scene.SourceMode

fun interface AudioFrameConsumer {
    fun onAudioFrame(samples: FloatArray, length: Int, sampleRate: Int)
}

interface AudioSource {
    val mode: SourceMode
    fun start()
    fun stop()
    fun release()
    fun setConsumer(consumer: AudioFrameConsumer)
}
