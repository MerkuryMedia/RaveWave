package com.ravewave.app.render

import android.opengl.GLES30
import com.ravewave.app.analyzer.AnalyzerMetrics
import com.ravewave.app.scene.SceneState
import com.ravewave.app.scene.VisualLayer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class MusicParticleSystem {
    private val particlesPerType = 96
    private val typeCount = 8
    private val total = particlesPerType * typeCount

    private val x = FloatArray(total)
    private val y = FloatArray(total)
    private val vx = FloatArray(total)
    private val vy = FloatArray(total)
    private val size = FloatArray(total)
    private val hue = FloatArray(total)
    private val alpha = FloatArray(total)
    private val life = FloatArray(total)
    private val depth = FloatArray(total)
    private val phase = FloatArray(total)

    private val vertexStrideFloats = 6
    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(total * vertexStrideFloats * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private var width = 1f
    private var height = 1f

    private var program = 0
    private var uResolution = -1
    private var aPosition = -1
    private var aSize = -1
    private var aHue = -1
    private var aAlpha = -1
    private var aDepth = -1

    fun onSurfaceCreated() {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uResolution = GLES30.glGetUniformLocation(program, "uResolution")
        aPosition = GLES30.glGetAttribLocation(program, "aPosition")
        aSize = GLES30.glGetAttribLocation(program, "aSize")
        aHue = GLES30.glGetAttribLocation(program, "aHue")
        aAlpha = GLES30.glGetAttribLocation(program, "aAlpha")
        aDepth = GLES30.glGetAttribLocation(program, "aDepth")
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        this.width = width.toFloat().coerceAtLeast(1f)
        this.height = height.toFloat().coerceAtLeast(1f)
        seedParticles()
    }

    fun update(scene: SceneState, metrics: AnalyzerMetrics, time: Float) {
        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val bassBoost = 1f + metrics.bass * 2.2f
        val energyBoost = 1f + metrics.energy * 1.7f
        val onsetBoost = 1f + metrics.onsetPulse * 1.2f

        for (i in 0 until total) {
            val type = i / particlesPerType
            val enabled = isLayerEnabled(type, scene)
            if (!enabled) {
                alpha[i] *= 0.9f
                continue
            }

            val depthScale = 0.45f + depth[i] * 1.3f
            alpha[i] = (alpha[i] * 0.92f + 0.05f + metrics.onsetPulse * 0.08f).coerceIn(0.1f, 1f)
            life[i] -= 0.008f * onsetBoost
            phase[i] += (0.01f + 0.02f * depth[i]) * energyBoost

            if (life[i] <= 0f) {
                resetParticle(i, type)
            }

            when (type) {
                0 -> { // Swarm
                    val angle = time * (0.7f + depth[i] * 0.8f) + phase[i]
                    vx[i] += cos(angle) * 0.05f * bassBoost * depthScale
                    vy[i] += sin(angle) * 0.05f * bassBoost * depthScale
                }
                1 -> { // Bouncing orbs
                    if (x[i] < -40f || x[i] > width + 40f) vx[i] *= -1f
                    if (y[i] < -40f || y[i] > height + 40f) vy[i] *= -1f
                    vx[i] += sin(time * 1.1f + phase[i]) * 0.03f * depthScale
                    vy[i] += cos(time * 0.9f + phase[i]) * 0.03f * depthScale
                }
                2 -> { // Comets
                    vx[i] += (0.05f + metrics.highs * 0.16f) * depthScale
                    vy[i] += sin(phase[i] + time) * 0.01f
                    alpha[i] = (alpha[i] + 0.02f).coerceAtMost(1f)
                }
                3 -> { // Shards
                    val dx = x[i] - centerX
                    val dy = y[i] - centerY
                    val ang = atan2(dy, dx)
                    vx[i] += cos(ang) * (0.03f + metrics.attackMod * 0.12f) * depthScale
                    vy[i] += sin(ang) * (0.03f + metrics.attackMod * 0.12f) * depthScale
                }
                4 -> { // Orbiters
                    val dx = x[i] - centerX
                    val dy = y[i] - centerY
                    val orbit = 0.00018f * (0.7f + depth[i])
                    vx[i] += -dy * orbit * energyBoost
                    vy[i] += dx * orbit * energyBoost
                    vx[i] += -dx * 0.000012f
                    vy[i] += -dy * 0.000012f
                }
                5 -> { // Ribbons
                    vx[i] += sin(time * 1.4f + phase[i]) * 0.05f * depthScale
                    vy[i] += cos(time * 1.1f + phase[i] * 1.6f) * 0.04f * depthScale
                }
                6 -> { // Neon rain
                    vy[i] += (0.12f + metrics.highs * 0.26f) * depthScale
                    vx[i] += sin(phase[i] + time * 0.6f) * 0.01f
                }
                7 -> { // Bass bubbles
                    vy[i] -= (0.08f + metrics.bass * 0.22f) * depthScale
                    vx[i] += sin(time + phase[i]) * 0.018f * depthScale
                }
            }

            vx[i] *= 0.985f
            vy[i] *= 0.985f

            x[i] += vx[i] * energyBoost
            y[i] += vy[i] * energyBoost

            when (type) {
                2 -> if (x[i] > width + 80f) resetParticle(i, type)
                3 -> if (x[i] < -120f || x[i] > width + 120f || y[i] < -120f || y[i] > height + 120f) resetParticle(i, type)
                6 -> if (y[i] > height + 80f) resetParticle(i, type)
                7 -> if (y[i] < -80f) resetParticle(i, type)
                else -> {
                    if (x[i] < -80f) x[i] = width + 80f
                    if (x[i] > width + 80f) x[i] = -80f
                    if (y[i] < -80f) y[i] = height + 80f
                    if (y[i] > height + 80f) y[i] = -80f
                }
            }

            size[i] = (size[i] * 0.94f + (3.5f + metrics.energy * 10f + type * 0.55f) * depthScale * 0.06f)
        }

        writeVertexBuffer()
    }

    fun draw() {
        if (program == 0) return

        GLES30.glUseProgram(program)
        GLES30.glUniform2f(uResolution, width, height)

        vertexBuffer.position(0)
        GLES30.glEnableVertexAttribArray(aPosition)
        GLES30.glVertexAttribPointer(aPosition, 2, GLES30.GL_FLOAT, false, vertexStrideFloats * 4, vertexBuffer)

        vertexBuffer.position(2)
        GLES30.glEnableVertexAttribArray(aSize)
        GLES30.glVertexAttribPointer(aSize, 1, GLES30.GL_FLOAT, false, vertexStrideFloats * 4, vertexBuffer)

        vertexBuffer.position(3)
        GLES30.glEnableVertexAttribArray(aHue)
        GLES30.glVertexAttribPointer(aHue, 1, GLES30.GL_FLOAT, false, vertexStrideFloats * 4, vertexBuffer)

        vertexBuffer.position(4)
        GLES30.glEnableVertexAttribArray(aAlpha)
        GLES30.glVertexAttribPointer(aAlpha, 1, GLES30.GL_FLOAT, false, vertexStrideFloats * 4, vertexBuffer)

        vertexBuffer.position(5)
        GLES30.glEnableVertexAttribArray(aDepth)
        GLES30.glVertexAttribPointer(aDepth, 1, GLES30.GL_FLOAT, false, vertexStrideFloats * 4, vertexBuffer)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, total)
        GLES30.glDisable(GLES30.GL_BLEND)

        GLES30.glDisableVertexAttribArray(aPosition)
        GLES30.glDisableVertexAttribArray(aSize)
        GLES30.glDisableVertexAttribArray(aHue)
        GLES30.glDisableVertexAttribArray(aAlpha)
        GLES30.glDisableVertexAttribArray(aDepth)
    }

    private fun writeVertexBuffer() {
        vertexBuffer.position(0)
        for (i in 0 until total) {
            vertexBuffer.put(x[i])
            vertexBuffer.put(y[i])
            vertexBuffer.put(size[i])
            vertexBuffer.put(hue[i])
            vertexBuffer.put(alpha[i])
            vertexBuffer.put(depth[i])
        }
        vertexBuffer.position(0)
    }

    private fun seedParticles() {
        for (i in 0 until total) {
            val type = i / particlesPerType
            resetParticle(i, type)
        }
        writeVertexBuffer()
    }

    private fun resetParticle(index: Int, type: Int) {
        val centerX = width * 0.5f
        val centerY = height * 0.5f
        depth[index] = Random.nextFloat().coerceIn(0.08f, 1f)
        phase[index] = Random.nextFloat() * 6.28318f
        size[index] = 2f + Random.nextFloat() * (5f + type * 0.4f)
        hue[index] = 160f + type * 24f + Random.nextFloat() * 28f
        alpha[index] = 0.12f + Random.nextFloat() * 0.2f
        life[index] = 0.45f + Random.nextFloat() * 1.4f

        when (type) {
            0 -> { // Swarm
                x[index] = centerX + (Random.nextFloat() - 0.5f) * width * 0.35f
                y[index] = centerY + (Random.nextFloat() - 0.5f) * height * 0.35f
                vx[index] = (Random.nextFloat() - 0.5f) * 1.5f
                vy[index] = (Random.nextFloat() - 0.5f) * 1.5f
            }
            1 -> { // Bouncing orbs
                x[index] = Random.nextFloat() * width
                y[index] = Random.nextFloat() * height
                vx[index] = (Random.nextFloat() - 0.5f) * 3.2f
                vy[index] = (Random.nextFloat() - 0.5f) * 3.2f
                size[index] += 2f
            }
            2 -> { // Comets
                x[index] = -Random.nextFloat() * width * 0.3f
                y[index] = Random.nextFloat() * height
                vx[index] = 2.4f + Random.nextFloat() * 4.0f
                vy[index] = (Random.nextFloat() - 0.5f) * 0.8f
                size[index] *= 0.9f
            }
            3 -> { // Shards
                x[index] = centerX + (Random.nextFloat() - 0.5f) * width * 0.08f
                y[index] = centerY + (Random.nextFloat() - 0.5f) * height * 0.08f
                val angle = Random.nextFloat() * 6.28318f
                val speed = 1.8f + Random.nextFloat() * 4.2f
                vx[index] = cos(angle) * speed
                vy[index] = sin(angle) * speed
                size[index] *= 0.85f
            }
            4 -> { // Orbiters
                val radius = width.coerceAtMost(height) * (0.14f + Random.nextFloat() * 0.24f)
                val angle = Random.nextFloat() * 6.28318f
                x[index] = centerX + cos(angle) * radius
                y[index] = centerY + sin(angle) * radius
                vx[index] = -sin(angle) * (1.0f + Random.nextFloat() * 1.3f)
                vy[index] = cos(angle) * (1.0f + Random.nextFloat() * 1.3f)
                size[index] *= 0.8f
            }
            5 -> { // Ribbons
                x[index] = Random.nextFloat() * width
                y[index] = centerY + (Random.nextFloat() - 0.5f) * height * 0.4f
                vx[index] = (Random.nextFloat() - 0.5f) * 1.6f
                vy[index] = (Random.nextFloat() - 0.5f) * 1.6f
            }
            6 -> { // Neon rain
                x[index] = Random.nextFloat() * width
                y[index] = -Random.nextFloat() * height
                vx[index] = (Random.nextFloat() - 0.5f) * 0.7f
                vy[index] = 2.6f + Random.nextFloat() * 5.4f
                size[index] *= 0.7f
            }
            7 -> { // Bass bubbles
                x[index] = Random.nextFloat() * width
                y[index] = height + Random.nextFloat() * height * 0.4f
                vx[index] = (Random.nextFloat() - 0.5f) * 0.9f
                vy[index] = -(0.8f + Random.nextFloat() * 1.8f)
                size[index] += 1.5f
            }
        }
    }

    private fun isLayerEnabled(type: Int, scene: SceneState): Boolean {
        val layer = when (type) {
            0 -> VisualLayer.SWARM
            1 -> VisualLayer.BOUNCERS
            2 -> VisualLayer.COMETS
            3 -> VisualLayer.SHARDS
            4 -> VisualLayer.ORBITERS
            5 -> VisualLayer.RIBBONS
            6 -> VisualLayer.RAIN
            7 -> VisualLayer.BUBBLES
            else -> VisualLayer.SWARM
        }
        return scene.enabledLayers.contains(layer)
    }

    companion object {
        private const val VERTEX_SHADER = """
            #version 300 es
            precision mediump float;
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in float aSize;
            layout(location = 2) in float aHue;
            layout(location = 3) in float aAlpha;
            layout(location = 4) in float aDepth;
            uniform vec2 uResolution;
            out float vHue;
            out float vAlpha;
            out float vDepth;
            void main() {
                vec2 center = uResolution * 0.5;
                float spread = 0.7 + aDepth * 0.9;
                vec2 projected = center + (aPosition - center) * spread;
                vec2 clip = vec2(
                    (projected.x / uResolution.x) * 2.0 - 1.0,
                    1.0 - (projected.y / uResolution.y) * 2.0
                );
                gl_Position = vec4(clip, 0.0, 1.0);
                gl_PointSize = aSize * (0.7 + aDepth * 1.5);
                vHue = aHue;
                vAlpha = aAlpha * (0.45 + aDepth * 0.7);
                vDepth = aDepth;
            }
        """

        private const val FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;
            in float vHue;
            in float vAlpha;
            in float vDepth;
            out vec4 fragColor;

            vec3 hsb2rgb(vec3 c) {
                vec3 rgb = clamp(abs(mod(c.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
                rgb = rgb * rgb * (3.0 - 2.0 * rgb);
                return c.z * mix(vec3(1.0), rgb, c.y);
            }

            void main() {
                vec2 p = gl_PointCoord * 2.0 - 1.0;
                float d = dot(p, p);
                float core = smoothstep(1.0, 0.08, d);
                float halo = exp(-d * (2.4 - vDepth * 1.2));
                vec3 color = hsb2rgb(vec3(fract(vHue / 360.0), 0.82, 1.0));
                color += vec3(1.0) * halo * 0.18;
                fragColor = vec4(color, (core * 0.78 + halo * 0.22) * vAlpha);
            }
        """
    }
}
