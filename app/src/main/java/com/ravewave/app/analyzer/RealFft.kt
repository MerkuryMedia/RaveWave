package com.ravewave.app.analyzer

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

class RealFft(private val size: Int) {
    private val real = FloatArray(size)
    private val imag = FloatArray(size)
    private val twiddleCos = FloatArray(size / 2)
    private val twiddleSin = FloatArray(size / 2)

    init {
        require(size and (size - 1) == 0) { "FFT size must be power of two" }
        for (i in 0 until size / 2) {
            val angle = -2.0 * Math.PI * i / size
            twiddleCos[i] = cos(angle).toFloat()
            twiddleSin[i] = sin(angle).toFloat()
        }
    }

    fun forwardMagnitude(input: FloatArray, outputMagnitude: FloatArray) {
        for (i in 0 until size) {
            real[i] = input[i]
            imag[i] = 0f
        }

        bitReverse(real, imag)

        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                var k = 0
                for (j in 0 until half) {
                    val tCos = twiddleCos[k]
                    val tSin = twiddleSin[k]

                    val evenReal = real[i + j]
                    val evenImag = imag[i + j]
                    val oddReal = real[i + j + half]
                    val oddImag = imag[i + j + half]

                    val tmpReal = oddReal * tCos - oddImag * tSin
                    val tmpImag = oddReal * tSin + oddImag * tCos

                    real[i + j] = evenReal + tmpReal
                    imag[i + j] = evenImag + tmpImag
                    real[i + j + half] = evenReal - tmpReal
                    imag[i + j + half] = evenImag - tmpImag
                    k += step
                }
                i += len
            }
            len *= 2
        }

        val halfSize = size / 2
        for (i in 0 until halfSize) {
            val r = real[i]
            val im = imag[i]
            outputMagnitude[i] = kotlin.math.sqrt(r * r + im * im) / halfSize
        }
    }

    private fun bitReverse(real: FloatArray, imag: FloatArray) {
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr

                val ti = imag[i]
                imag[i] = imag[j]
                imag[j] = ti
            }
        }
    }
}
