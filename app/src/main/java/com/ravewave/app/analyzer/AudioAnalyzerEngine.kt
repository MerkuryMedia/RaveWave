package com.ravewave.app.analyzer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max

class AudioAnalyzerEngine(
    private val fftSize: Int = 1024,
    private val waveformSize: Int = 512
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var processingJob: Job? = null

    private val fft = RealFft(fftSize)
    private val ringBuffer = FloatArray(fftSize * 8)
    private val analysisWindow = FloatArray(fftSize)
    private val waveform = FloatArray(waveformSize)
    private val fftBins = FloatArray(fftSize / 2)
    private val hann = FloatArray(fftSize)

    private var writeIndex = 0
    private var sampleCount = 0
    private var latestSampleRate = 48000

    private var smoothedBass = 0f
    private var smoothedMids = 0f
    private var smoothedHighs = 0f
    private var smoothedEnergy = 0f
    private var peakHold = 0f
    private var previousFlux = 0f
    private var beatEnvelope = 0f
    private var onsetEnvelope = 0f
    private var attackMod = 0f
    private var decayMod = 0f

    private var frameIndex = 0L
    private val lock = Any()

    private val _metrics = MutableStateFlow(
        AnalyzerMetrics(
            frameIndex = 0,
            bass = 0f,
            mids = 0f,
            highs = 0f,
            energy = 0f,
            onsetPulse = 0f,
            beatPulse = 0f,
            peakHold = 0f,
            attackMod = 0f,
            decayMod = 0f
        )
    )
    val metrics: StateFlow<AnalyzerMetrics> = _metrics.asStateFlow()

    init {
        for (i in hann.indices) {
            hann[i] = (0.5f - 0.5f * kotlin.math.cos((2.0 * Math.PI * i / (fftSize - 1)).toFloat()))
        }
    }

    fun start() {
        if (processingJob?.isActive == true) return
        processingJob = scope.launch {
            while (isActive) {
                processFrame()
                delay(16L)
            }
        }
    }

    fun stop() {
        processingJob?.cancel()
        processingJob = null
    }

    fun submitPcm(samples: FloatArray, length: Int, sampleRate: Int) {
        if (length <= 0) return
        synchronized(lock) {
            latestSampleRate = sampleRate
            for (i in 0 until length) {
                ringBuffer[writeIndex] = samples[i]
                writeIndex = (writeIndex + 1) % ringBuffer.size
                sampleCount = minOf(sampleCount + 1, ringBuffer.size)
            }
        }
    }

    fun snapshotWaveform(out: FloatArray): Int {
        val count = minOf(out.size, waveform.size)
        synchronized(waveform) {
            System.arraycopy(waveform, 0, out, 0, count)
        }
        return count
    }

    fun snapshotBins(out: FloatArray): Int {
        val count = minOf(out.size, fftBins.size)
        synchronized(fftBins) {
            System.arraycopy(fftBins, 0, out, 0, count)
        }
        return count
    }

    private suspend fun processFrame() {
        synchronized(lock) {
            if (sampleCount < fftSize) return
            val start = (writeIndex - fftSize + ringBuffer.size) % ringBuffer.size
            for (i in 0 until fftSize) {
                val sample = ringBuffer[(start + i) % ringBuffer.size]
                analysisWindow[i] = sample * hann[i]
            }

            val waveStart = (writeIndex - waveformSize + ringBuffer.size) % ringBuffer.size
            synchronized(waveform) {
                for (i in 0 until waveformSize) {
                    waveform[i] = ringBuffer[(waveStart + i) % ringBuffer.size]
                }
            }
        }

        fft.forwardMagnitude(analysisWindow, fftBins)
        normalizeBinsInPlace()

        val bass = averageBand(0f, 180f)
        val mids = averageBand(180f, 2200f)
        val highs = averageBand(2200f, 9000f)
        val energy = (bass + mids + highs) / 3f

        smoothedBass = smooth(smoothedBass, bass, 0.22f)
        smoothedMids = smooth(smoothedMids, mids, 0.18f)
        smoothedHighs = smooth(smoothedHighs, highs, 0.15f)
        smoothedEnergy = smooth(smoothedEnergy, energy, 0.16f)

        val flux = spectralFlux()
        val onset = (flux - previousFlux * 0.6f).coerceAtLeast(0f)
        previousFlux = flux

        onsetEnvelope = max(onsetEnvelope * 0.82f, onset * 2.2f)
        val beatTrigger = (smoothedBass * 0.72f + onset * 0.48f) - smoothedEnergy * 0.35f
        beatEnvelope = max(beatEnvelope * 0.9f, beatTrigger.coerceAtLeast(0f))

        peakHold = max(peakHold * 0.985f, smoothedEnergy)

        val energyDelta = smoothedEnergy - _metrics.value.energy
        attackMod = smooth(attackMod, energyDelta.coerceAtLeast(0f) * 4f, 0.3f)
        decayMod = smooth(decayMod, abs(energyDelta.coerceAtMost(0f)) * 2f, 0.18f)

        frameIndex += 1
        _metrics.value = AnalyzerMetrics(
            frameIndex = frameIndex,
            bass = smoothedBass,
            mids = smoothedMids,
            highs = smoothedHighs,
            energy = smoothedEnergy,
            onsetPulse = onsetEnvelope.coerceIn(0f, 1f),
            beatPulse = beatEnvelope.coerceIn(0f, 1f),
            peakHold = peakHold.coerceIn(0f, 1f),
            attackMod = attackMod.coerceIn(0f, 1f),
            decayMod = decayMod.coerceIn(0f, 1f)
        )
    }

    private fun normalizeBinsInPlace() {
        var maxValue = 1e-6f
        for (v in fftBins) {
            if (v > maxValue) maxValue = v
        }
        val inv = 1f / maxValue
        synchronized(fftBins) {
            for (i in fftBins.indices) {
                fftBins[i] = (fftBins[i] * inv).coerceIn(0f, 1f)
            }
        }
    }

    private fun spectralFlux(): Float {
        var sum = 0f
        for (i in 1 until fftBins.size) {
            val diff = fftBins[i] - fftBins[i - 1]
            if (diff > 0f) sum += diff
        }
        return (sum / fftBins.size).coerceIn(0f, 1f)
    }

    private fun averageBand(lowHz: Float, highHz: Float): Float {
        val binSize = latestSampleRate / fftSize.toFloat()
        val start = (lowHz / binSize).toInt().coerceAtLeast(0)
        val end = (highHz / binSize).toInt().coerceAtMost(fftBins.size - 1)
        if (end <= start) return 0f

        var sum = 0f
        var count = 0
        for (i in start..end) {
            sum += fftBins[i]
            count++
        }
        return if (count == 0) 0f else (sum / count).coerceIn(0f, 1f)
    }

    private fun smooth(current: Float, target: Float, alpha: Float): Float {
        return current + (target - current) * alpha
    }
}
