package com.ravewave.app.render

import android.opengl.GLES30
import com.ravewave.app.analyzer.AnalyzerMetrics
import com.ravewave.app.scene.SceneState
import com.ravewave.app.scene.VisualLayer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
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

    private val vertexStrideFloats = 5
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

    fun onSurfaceCreated() {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uResolution = GLES30.glGetUniformLocation(program, "uResolution")
        aPosition = GLES30.glGetAttribLocation(program, "aPosition")
        aSize = GLES30.glGetAttribLocation(program, "aSize")
        aHue = GLES30.glGetAttribLocation(program, "aHue")
        aAlpha = GLES30.glGetAttribLocation(program, "aAlpha")
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        this.width = width.toFloat().coerceAtLeast(1f)
        this.height = height.toFloat().coerceAtLeast(1f)
        seedParticles()
    }

    fun update(scene: SceneState, metrics: AnalyzerMetrics, time: Float) {
        val bassBoost = 1f + metrics.bass * 2.4f
        val energyBoost = 1f + metrics.energy * 1.6f

        for (i in 0 until total) {
            val type = i / particlesPerType
            val enabled = isLayerEnabled(type, scene)
            if (!enabled) {
                alpha[i] *= 0.92f
                continue
            }

            alpha[i] = (alpha[i] + 0.04f + metrics.onsetPulse * 0.06f).coerceIn(0.15f, 1f)
            life[i] -= 0.01f
            if (life[i] <= 0f) {
                resetParticle(i, type)
            }

            when (type) {
                0 -> { // Swarm
                    val angle = time * 0.8f + i * 0.03f
                    vx[i] += cos(angle) * 0.05f * bassBoost
                    vy[i] += sin(angle) * 0.05f * bassBoost
                }
                1 -> { // Bouncers
                    if (x[i] < 0f || x[i] > width) vx[i] *= -1f
                    if (y[i] < 0f || y[i] > height) vy[i] *= -1f
                }
                2 -> { // Comets
                    vx[i] += 0.02f + metrics.highs * 0.06f
                }
                3 -> { // Shards
                    vx[i] += sin(time + i) * 0.03f
                    vy[i] += cos(time + i * 0.8f) * 0.03f
                }
                4 -> { // Orbiters
                    val ox = width * 0.5f
                    val oy = height * 0.5f
                    val dx = ox - x[i]
                    val dy = oy - y[i]
                    vx[i] += dx * 0.00002f * energyBoost
                    vy[i] += dy * 0.00002f * energyBoost
                }
                5 -> { // Ribbons
                    vx[i] += sin(time * 1.4f + i) * 0.04f
                    vy[i] += cos(time * 1.1f + i) * 0.04f
                }
                6 -> { // Neon rain
                    vy[i] += 0.08f + metrics.highs * 0.18f
                }
                7 -> { // Bass bubbles
                    vy[i] -= 0.04f + metrics.bass * 0.12f
                    vx[i] += sin(time + i) * 0.015f
                }
            }

            x[i] += vx[i] * energyBoost
            y[i] += vy[i] * energyBoost

            if (x[i] < -40f) x[i] = width + 40f
            if (x[i] > width + 40f) x[i] = -40f
            if (y[i] < -40f) y[i] = height + 40f
            if (y[i] > height + 40f) y[i] = -40f

            size[i] = (size[i] * 0.98f + (4f + metrics.energy * 9f + type * 0.5f) * 0.02f)
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

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, total)
        GLES30.glDisable(GLES30.GL_BLEND)

        GLES30.glDisableVertexAttribArray(aPosition)
        GLES30.glDisableVertexAttribArray(aSize)
        GLES30.glDisableVertexAttribArray(aHue)
        GLES30.glDisableVertexAttribArray(aAlpha)
    }

    private fun writeVertexBuffer() {
        vertexBuffer.position(0)
        for (i in 0 until total) {
            vertexBuffer.put(x[i])
            vertexBuffer.put(y[i])
            vertexBuffer.put(size[i])
            vertexBuffer.put(hue[i])
            vertexBuffer.put(alpha[i])
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
        x[index] = Random.nextFloat() * width
        y[index] = Random.nextFloat() * height
        vx[index] = (Random.nextFloat() - 0.5f) * (1.2f + type * 0.2f)
        vy[index] = (Random.nextFloat() - 0.5f) * (1.2f + type * 0.2f)
        size[index] = 2f + Random.nextFloat() * 6f
        hue[index] = 160f + type * 26f + Random.nextFloat() * 20f
        alpha[index] = 0.15f + Random.nextFloat() * 0.2f
        life[index] = 0.4f + Random.nextFloat() * 1.1f

        if (type == 6) { // rain starts higher
            y[index] = Random.nextFloat() * height - height
            vy[index] = 1.2f + Random.nextFloat() * 2.8f
        }
        if (type == 7) { // bubbles start low
            y[index] = height + Random.nextFloat() * height * 0.5f
            vy[index] = -(0.3f + Random.nextFloat() * 1.4f)
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
            uniform vec2 uResolution;
            out float vHue;
            out float vAlpha;
            void main() {
                vec2 clip = vec2(
                    (aPosition.x / uResolution.x) * 2.0 - 1.0,
                    1.0 - (aPosition.y / uResolution.y) * 2.0
                );
                gl_Position = vec4(clip, 0.0, 1.0);
                gl_PointSize = aSize;
                vHue = aHue;
                vAlpha = aAlpha;
            }
        """

        private const val FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;
            in float vHue;
            in float vAlpha;
            out vec4 fragColor;

            vec3 hsb2rgb(vec3 c) {
                vec3 rgb = clamp(abs(mod(c.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
                rgb = rgb * rgb * (3.0 - 2.0 * rgb);
                return c.z * mix(vec3(1.0), rgb, c.y);
            }

            void main() {
                vec2 p = gl_PointCoord * 2.0 - 1.0;
                float d = dot(p, p);
                float mask = smoothstep(1.0, 0.25, d);
                vec3 color = hsb2rgb(vec3(fract(vHue / 360.0), 0.85, 1.0));
                fragColor = vec4(color, mask * vAlpha);
            }
        """
    }
}
