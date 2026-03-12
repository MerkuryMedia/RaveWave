package com.ravewave.app.render

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.ravewave.app.analyzer.AnalyzerMetrics
import com.ravewave.app.analyzer.AudioAnalyzerEngine
import com.ravewave.app.scene.ColorMode
import com.ravewave.app.scene.PostEffect
import com.ravewave.app.scene.SceneState
import com.ravewave.app.scene.VisualLayer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VisualizerSurfaceView(
    context: Context,
    analyzerEngine: AudioAnalyzerEngine
) : GLSurfaceView(context) {

    private val renderer = VisualizerRenderer(analyzerEngine)

    init {
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
    }

    fun updateSceneState(state: SceneState) {
        renderer.updateSceneState(state)
    }
}

class VisualizerRenderer(
    private val analyzerEngine: AudioAnalyzerEngine
) : GLSurfaceView.Renderer {

    private val sceneState = AtomicReference(SceneState())

    private var width = 1
    private var height = 1

    private val quadVertices = floatArrayOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f
    )
    private val quadBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(quadVertices.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(quadVertices).apply { position(0) }

    private var sceneProgram = 0
    private var postProgram = 0

    private var scenePositionLoc = -1
    private var postPositionLoc = -1
    private var sceneUTime = -1
    private var sceneUResolution = -1
    private var sceneUAudio = -1
    private var sceneUPulse = -1
    private var sceneULayerEnabled = -1
    private var sceneUFftTex = -1
    private var sceneUWaveTex = -1

    private var postUTime = -1
    private var postUResolution = -1
    private var postUFxIntensity = -1
    private var postUColorMode = -1
    private var postUSpeed = -1
    private var postUTileCount = -1
    private var postUSymmetry = -1
    private var postUAudio = -1
    private var postUPulse = -1
    private var postUEffectEnabled = -1
    private var postUSceneTex = -1

    private var sceneFbo = FrameBuffer()
    private val particles = MusicParticleSystem()

    private var fftTexture = 0
    private var waveTexture = 0

    private val fftSamples = FloatArray(512)
    private val waveSamples = FloatArray(512)
    private val fftBytes = ByteArray(512)
    private val waveBytes = ByteArray(512)
    private val fftUploadBuffer: ByteBuffer = ByteBuffer.allocateDirect(fftBytes.size)
    private val waveUploadBuffer: ByteBuffer = ByteBuffer.allocateDirect(waveBytes.size)

    private val layerUniforms = FloatArray(VisualLayer.entries.size)
    private val effectUniforms = FloatArray(PostEffect.entries.size)

    private var startNanos = System.nanoTime()

    fun updateSceneState(state: SceneState) {
        sceneState.set(state)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)

        sceneProgram = GlUtil.createProgram(SCENE_VERTEX_SHADER, SCENE_FRAGMENT_SHADER)
        postProgram = GlUtil.createProgram(POST_VERTEX_SHADER, POST_FRAGMENT_SHADER)

        scenePositionLoc = GLES30.glGetAttribLocation(sceneProgram, "aPosition")
        postPositionLoc = GLES30.glGetAttribLocation(postProgram, "aPosition")

        sceneUTime = GLES30.glGetUniformLocation(sceneProgram, "uTime")
        sceneUResolution = GLES30.glGetUniformLocation(sceneProgram, "uResolution")
        sceneUAudio = GLES30.glGetUniformLocation(sceneProgram, "uAudio")
        sceneUPulse = GLES30.glGetUniformLocation(sceneProgram, "uPulse")
        sceneULayerEnabled = GLES30.glGetUniformLocation(sceneProgram, "uLayerEnabled[0]")
        sceneUFftTex = GLES30.glGetUniformLocation(sceneProgram, "uFftTex")
        sceneUWaveTex = GLES30.glGetUniformLocation(sceneProgram, "uWaveTex")

        postUTime = GLES30.glGetUniformLocation(postProgram, "uTime")
        postUResolution = GLES30.glGetUniformLocation(postProgram, "uResolution")
        postUFxIntensity = GLES30.glGetUniformLocation(postProgram, "uFxIntensity")
        postUColorMode = GLES30.glGetUniformLocation(postProgram, "uColorMode")
        postUSpeed = GLES30.glGetUniformLocation(postProgram, "uSpeed")
        postUTileCount = GLES30.glGetUniformLocation(postProgram, "uTileCount")
        postUSymmetry = GLES30.glGetUniformLocation(postProgram, "uSymmetry")
        postUAudio = GLES30.glGetUniformLocation(postProgram, "uAudio")
        postUPulse = GLES30.glGetUniformLocation(postProgram, "uPulse")
        postUEffectEnabled = GLES30.glGetUniformLocation(postProgram, "uEffectEnabled[0]")
        postUSceneTex = GLES30.glGetUniformLocation(postProgram, "uSceneTex")

        fftTexture = GlUtil.createAudioTexture(fftSamples.size)
        waveTexture = GlUtil.createAudioTexture(waveSamples.size)

        particles.onSurfaceCreated()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        GLES30.glViewport(0, 0, width, height)
        sceneFbo.ensureSize(width, height)
        particles.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val state = sceneState.get()
        val renderLayers = RenderBudget.resolveRenderableLayers(state, RenderBudget.Profile.NATIVE)
        val renderState = if (renderLayers.size == state.enabledLayers.size) {
            state
        } else {
            state.copy(enabledLayers = renderLayers)
        }
        val metrics = analyzerEngine.metrics.value
        val time = ((System.nanoTime() - startNanos) / 1_000_000_000.0).toFloat()

        analyzerEngine.snapshotBins(fftSamples)
        analyzerEngine.snapshotWaveform(waveSamples)
        uploadAudioTextures()

        mapLayerUniforms(renderState.enabledLayers)
        mapEffectUniforms(state)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo.framebufferId)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        drawScenePass(metrics, time)

        particles.update(renderState, metrics, time)
        particles.draw()

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        drawPostPass(state, metrics, time)
    }

    private fun drawScenePass(metrics: AnalyzerMetrics, time: Float) {
        GLES30.glUseProgram(sceneProgram)
        GLES30.glUniform1f(sceneUTime, time)
        GLES30.glUniform2f(sceneUResolution, width.toFloat(), height.toFloat())
        GLES30.glUniform4f(
            sceneUAudio,
            metrics.bass,
            metrics.mids,
            metrics.highs,
            metrics.energy
        )
        GLES30.glUniform4f(
            sceneUPulse,
            metrics.beatPulse,
            metrics.onsetPulse,
            metrics.attackMod,
            metrics.decayMod
        )

        GLES30.glUniform1fv(sceneULayerEnabled, layerUniforms.size, layerUniforms, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fftTexture)
        GLES30.glUniform1i(sceneUFftTex, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, waveTexture)
        GLES30.glUniform1i(sceneUWaveTex, 1)

        drawFullscreenQuad(scenePositionLoc)
    }

    private fun drawPostPass(state: SceneState, metrics: AnalyzerMetrics, time: Float) {
        GLES30.glUseProgram(postProgram)
        GLES30.glUniform1f(postUTime, time)
        GLES30.glUniform2f(postUResolution, width.toFloat(), height.toFloat())
        GLES30.glUniform1f(postUFxIntensity, state.fxIntensity)
        GLES30.glUniform1f(postUColorMode, state.colorMode.index.toFloat())
        GLES30.glUniform1f(postUSpeed, state.speed)
        GLES30.glUniform1f(postUTileCount, state.tileCount.toFloat())
        GLES30.glUniform1f(postUSymmetry, state.symmetrySegments.toFloat())
        GLES30.glUniform4f(
            postUAudio,
            metrics.bass,
            metrics.mids,
            metrics.highs,
            metrics.energy
        )
        GLES30.glUniform4f(
            postUPulse,
            metrics.beatPulse,
            metrics.onsetPulse,
            metrics.attackMod,
            metrics.decayMod
        )

        GLES30.glUniform1fv(postUEffectEnabled, effectUniforms.size, effectUniforms, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneFbo.textureId)
        GLES30.glUniform1i(postUSceneTex, 0)

        drawFullscreenQuad(postPositionLoc)
    }

    private fun drawFullscreenQuad(positionLocation: Int) {
        quadBuffer.position(0)
        GLES30.glEnableVertexAttribArray(positionLocation)
        GLES30.glVertexAttribPointer(positionLocation, 2, GLES30.GL_FLOAT, false, 2 * 4, quadBuffer)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(positionLocation)
    }

    private fun uploadAudioTextures() {
        for (i in fftSamples.indices) {
            fftBytes[i] = (fftSamples[i].coerceIn(0f, 1f) * 255f).toInt().toByte()
            waveBytes[i] = (((waveSamples[i] * 0.5f + 0.5f).coerceIn(0f, 1f)) * 255f).toInt().toByte()
        }

        fftUploadBuffer.position(0)
        fftUploadBuffer.put(fftBytes)
        fftUploadBuffer.position(0)

        waveUploadBuffer.position(0)
        waveUploadBuffer.put(waveBytes)
        waveUploadBuffer.position(0)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fftTexture)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            0,
            0,
            fftSamples.size,
            1,
            GLES30.GL_RED,
            GLES30.GL_UNSIGNED_BYTE,
            fftUploadBuffer
        )

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, waveTexture)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            0,
            0,
            waveSamples.size,
            1,
            GLES30.GL_RED,
            GLES30.GL_UNSIGNED_BYTE,
            waveUploadBuffer
        )
    }

    private fun mapLayerUniforms(enabledLayers: Set<VisualLayer>) {
        layerUniforms.fill(0f)
        for (layer in enabledLayers) {
            layerUniforms[layer.index] = 1f
        }
    }

    private fun mapEffectUniforms(state: SceneState) {
        effectUniforms.fill(0f)
        for (effect in state.enabledEffects) {
            effectUniforms[effect.index] = 1f
        }
    }

    companion object {
        private const val SCENE_VERTEX_SHADER = """
            #version 300 es
            precision mediump float;
            in vec2 aPosition;
            out vec2 vUv;
            void main() {
                vUv = aPosition * 0.5 + 0.5;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        private const val SCENE_FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            in vec2 vUv;
            out vec4 fragColor;

            uniform sampler2D uFftTex;
            uniform sampler2D uWaveTex;
            uniform vec2 uResolution;
            uniform vec4 uAudio;
            uniform vec4 uPulse;
            uniform float uTime;
            uniform float uLayerEnabled[29];

            const float PI = 3.141592653589793;
            const float TAU = 6.283185307179586;

            float fftAt(float x) {
                return texture(uFftTex, vec2(clamp(x, 0.0, 1.0), 0.5)).r;
            }

            float waveAt(float x) {
                return texture(uWaveTex, vec2(clamp(x, 0.0, 1.0), 0.5)).r * 2.0 - 1.0;
            }

            vec3 palette(float t) {
                vec3 a = vec3(0.55, 0.50, 0.52);
                vec3 b = vec3(0.45, 0.28, 0.40);
                vec3 c = vec3(1.00, 0.85, 0.65);
                vec3 d = vec3(0.05, 0.18, 0.33);
                return a + b * cos(TAU * (c * t + d));
            }

            mat2 rot(float a) {
                float s = sin(a);
                float c = cos(a);
                return mat2(c, -s, s, c);
            }

            float glow(float d, float width) {
                return exp(-abs(d) * width);
            }

            float hash21(vec2 p) {
                p = fract(p * vec2(123.34, 345.45));
                p += dot(p, p + 34.345);
                return fract(p.x * p.y);
            }

            float noise(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                float a = hash21(i);
                float b = hash21(i + vec2(1.0, 0.0));
                float c = hash21(i + vec2(0.0, 1.0));
                float d = hash21(i + vec2(1.0, 1.0));
                vec2 u = f * f * (3.0 - 2.0 * f);
                return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
            }

            float fbm(vec2 p) {
                float v = 0.0;
                float a = 0.5;
                for (int i = 0; i < 4; i++) {
                    v += noise(p) * a;
                    p = rot(0.6) * p * 2.1 + vec2(1.7, -1.2);
                    a *= 0.5;
                }
                return v;
            }

            float lineSeg(vec2 p, vec2 a, vec2 b, float width) {
                vec2 pa = p - a;
                vec2 ba = b - a;
                float h = clamp(dot(pa, ba) / max(dot(ba, ba), 0.0001), 0.0, 1.0);
                return exp(-length(pa - ba * h) * width);
            }

            float circleLine(vec2 p, float radius, float width) {
                return glow(length(p) - radius, width);
            }

            float starField(vec2 uv, float scale) {
                vec2 gv = fract(uv * scale) - 0.5;
                vec2 id = floor(uv * scale);
                float n = hash21(id);
                vec2 offset = vec2(hash21(id + 1.7), hash21(id + 9.2)) - 0.5;
                float d = length(gv - offset * 0.36);
                float core = smoothstep(0.09, 0.0, d);
                return core * pow(n, 10.0);
            }

            float equiTriangle(vec2 p, vec2 center, float scale) {
                vec2 q = (p - center) / max(scale, 0.0001);
                q.y += 0.18;
                q.x = abs(q.x);
                return max(q.x * 0.8660254 + q.y * 0.5, -q.y) - 0.5;
            }

            void main() {
                vec2 p = vUv * 2.0 - 1.0;
                p.x *= uResolution.x / max(uResolution.y, 1.0);

                float bass = uAudio.x;
                float mids = uAudio.y;
                float highs = uAudio.z;
                float energy = uAudio.w;
                float beat = uPulse.x;
                float onset = uPulse.y;
                float attack = uPulse.z;

                float radius = length(p);
                float ang = atan(p.y, p.x);
                vec2 uv = vec2(
                    p.x / (uResolution.x / max(uResolution.y, 1.0)),
                    p.y
                ) * 0.5 + 0.5;

                vec3 color = vec3(0.0);

                float stars = starField(uv + vec2(uTime * 0.005, 0.0), 48.0);
                stars += starField(uv * 1.9 + vec2(0.0, uTime * 0.004), 86.0);
                float nebula = fbm(p * 1.5 + vec2(uTime * 0.05, -uTime * 0.03));
                color += vec3(1.0) * stars * (0.45 + highs * 0.8);
                color += palette(nebula * 0.3 + radius * 0.08 + uTime * 0.02) * (0.08 + energy * 0.08) * exp(-radius * 2.2);
                color += vec3(0.04, 0.02, 0.08) * onset * exp(-radius * 3.0);

                if (uLayerEnabled[0] > 0.5) {
                    for (int i = 0; i < 24; i++) {
                        float fi = float(i);
                        float t = fi / 24.0;
                        float a = fftAt(t);
                        float x = mix(-1.0, 1.0, t);
                        float width = 0.018 + a * 0.01;
                        float top = -0.85 + a * (1.05 + beat * 0.25);
                        float body = smoothstep(width, 0.0, abs(p.x - x)) * smoothstep(0.0, 0.05, p.y - top) * smoothstep(1.02, 0.88, p.y);
                        float cap = exp(-length(vec2((p.x - x) * 18.0, (p.y - top) * 42.0)));
                        color += palette(t + a * 0.25 + uTime * 0.03) * (body * 0.48 + cap * 0.75);
                    }
                }

                if (uLayerEnabled[1] > 0.5) {
                    float wave = waveAt(uv.x);
                    float offset = 0.26 + bass * 0.14;
                    float traceA = glow(p.y - wave * offset, 140.0);
                    float traceB = glow(p.y + wave * offset * 0.66, 180.0);
                    color += vec3(0.1, 0.96, 0.9) * traceA;
                    color += vec3(0.72, 0.34, 1.0) * traceB * 0.7;
                }

                if (uLayerEnabled[2] > 0.5) {
                    float band = fftAt(fract((ang + PI) / TAU));
                    float target = 0.26 + band * 0.34 + bass * 0.08;
                    float circular = glow(radius - target, 120.0);
                    float spokes = pow(max(0.0, cos(ang * (8.0 + highs * 8.0))), 6.0) * exp(-radius * 1.3);
                    color += palette(target + uTime * 0.04) * circular * 1.1;
                    color += vec3(1.0, 0.7, 0.25) * spokes * (0.12 + highs * 0.3);
                }

                if (uLayerEnabled[3] > 0.5) {
                    float meter = smoothstep(0.07, 0.0, abs(p.x)) * smoothstep(0.0, 0.06, p.y - (-1.0 + energy * 1.7));
                    float peak = glow(p.y - (-1.0 + (energy + attack * 0.2) * 1.7), 180.0);
                    color += vec3(0.18, 1.0, 0.46) * meter * 0.8;
                    color += vec3(1.0, 0.95, 0.65) * peak * 0.65;
                }

                if (uLayerEnabled[4] > 0.5) {
                    float core = exp(-radius * (7.0 - bass * 2.5));
                    float ring = glow(radius - (0.18 + bass * 0.1), 90.0);
                    float lens = sin((radius * 16.0 - uTime * (2.0 + bass * 6.0)) + ang * 4.0);
                    color += vec3(1.0, 0.78, 0.32) * core * (0.35 + bass * 0.7);
                    color += vec3(0.45, 0.82, 1.0) * ring * (0.2 + energy * 0.4);
                    color += palette(radius * 0.4 + 0.2) * smoothstep(0.86, 1.0, lens) * exp(-radius * 1.6) * 0.3;
                }

                if (uLayerEnabled[5] > 0.5) {
                    float spine = sin(p.y * 7.0 - uTime * 1.8);
                    float strandA = glow(p.x - spine * (0.18 + mids * 0.18), 85.0);
                    float strandB = glow(p.x + spine * (0.18 + mids * 0.18), 85.0);
                    float rung = glow(sin(p.y * 24.0 + uTime * 3.0) * 0.14 - p.x, 35.0) * exp(-abs(p.x) * 4.0);
                    color += vec3(0.2, 0.82, 1.0) * strandA * 0.8;
                    color += vec3(1.0, 0.36, 0.78) * strandB * 0.7;
                    color += vec3(1.0, 0.9, 0.62) * rung * 0.22;
                }

                if (uLayerEnabled[6] > 0.5) {
                    float flow = fbm(p * 2.8 + vec2(0.0, -uTime * 0.5));
                    float liquid = glow(sin(flow * 10.0 + p.x * 5.0 - uTime * 1.3), 4.0);
                    color += palette(flow + uTime * 0.03) * liquid * (0.16 + mids * 0.34) * exp(-radius * 0.8);
                }

                if (uLayerEnabled[7] > 0.5) {
                    for (int i = 0; i < 18; i++) {
                        float fi = float(i);
                        float t = fi / 18.0;
                        float a = fftAt(t * 0.7);
                        float x = mix(-1.0, 1.0, t);
                        float h = -0.92 + a * (0.7 + bass * 0.5);
                        float block = smoothstep(0.045, 0.0, abs(p.x - x)) * smoothstep(0.0, 0.03, p.y - h) * smoothstep(0.92, 0.72, p.y);
                        float reflect = smoothstep(0.0, 0.18, -p.y) * smoothstep(0.16, 0.0, abs(p.x - x)) * exp(-abs(p.y + 0.2) * 10.0);
                        color += vec3(0.15, 0.92, 1.0) * block * 0.42;
                        color += vec3(0.32, 0.6, 1.0) * reflect * 0.08;
                    }
                }

                if (uLayerEnabled[8] > 0.5) {
                    float network = 0.0;
                    for (int i = 0; i < 6; i++) {
                        float fi = float(i);
                        vec2 c = vec2(
                            sin(uTime * (0.4 + fi * 0.07) + fi * 1.4),
                            cos(uTime * (0.55 + fi * 0.05) + fi * 2.1)
                        ) * (0.22 + fi * 0.06);
                        network += exp(-length(p - c) * (18.0 - mids * 5.0));
                    }
                    float filaments = smoothstep(0.96, 1.0, sin((p.x + p.y) * 14.0 + fbm(p * 4.0 + uTime) * 6.0));
                    color += vec3(0.2, 0.82, 1.0) * network * 0.18;
                    color += vec3(0.7, 0.9, 1.0) * filaments * highs * 0.28;
                }

                if (uLayerEnabled[9] > 0.5) {
                    float orbitA = glow(radius - (0.34 + sin(uTime * 0.6) * 0.03), 120.0);
                    float orbitB = glow(radius - (0.58 + cos(uTime * 0.4) * 0.04), 110.0);
                    float satA = exp(-length(p - vec2(cos(uTime * 1.2), sin(uTime * 1.2)) * 0.34) * 48.0);
                    float satB = exp(-length(p - vec2(cos(-uTime * 0.8 + 1.6), sin(-uTime * 0.8 + 1.6)) * 0.58) * 44.0);
                    color += vec3(1.0, 0.92, 0.72) * orbitA * 0.4;
                    color += vec3(0.38, 0.82, 1.0) * orbitB * 0.3;
                    color += vec3(1.0, 0.6, 0.25) * satA;
                    color += vec3(0.8, 0.9, 1.0) * satB;
                }

                if (uLayerEnabled[10] > 0.5) {
                    vec2 swirl = rot(radius * (4.0 + bass * 6.0)) * p;
                    float disk = exp(-abs(swirl.y) * 12.0) * exp(-radius * 2.4);
                    float voidMask = smoothstep(0.22 + bass * 0.06, 0.0, radius);
                    float rim = glow(radius - (0.22 + bass * 0.08), 140.0);
                    color += vec3(1.0, 0.52, 0.12) * disk * 0.42;
                    color += vec3(0.3, 0.85, 1.0) * rim * 0.55;
                    color *= 1.0 - voidMask * 0.65;
                }

                if (uLayerEnabled[11] > 0.5) {
                    float facets = abs(fract((ang / TAU) * 12.0 + radius * 0.7) - 0.5);
                    float crystal = glow(facets - (0.12 + fftAt(fract((ang + PI) / TAU)) * 0.08), 55.0);
                    color += vec3(0.62, 0.86, 1.0) * crystal * exp(-radius * 0.8) * 0.6;
                }

                if (uLayerEnabled[12] > 0.5) {
                    float flameNoise = fbm(vec2(p.x * 1.8, p.y * 2.4 - uTime * 1.2));
                    float flame = smoothstep(0.72, 0.15, p.y + flameNoise * (0.28 + bass * 0.18) + 0.82);
                    flame *= smoothstep(0.9, 0.08, abs(p.x));
                    color += mix(vec3(1.0, 0.24, 0.04), vec3(1.0, 0.82, 0.28), flameNoise) * flame * (0.4 + bass * 0.5);
                }

                if (uLayerEnabled[13] > 0.5) {
                    float field = sin(ang * 3.0 + radius * 22.0 - uTime * (1.0 + mids * 4.0));
                    float magnetic = smoothstep(0.95, 1.0, field) * exp(-radius * 0.6);
                    color += vec3(0.3, 1.0, 0.84) * magnetic * 0.34;
                }

                if (uLayerEnabled[14] > 0.5) {
                    float petals = abs(sin(ang * 6.0 + uTime * 0.6));
                    float mandala = glow(radius - (0.28 + petals * 0.18 + fftAt(fract((ang + PI) / TAU)) * 0.08), 120.0);
                    color += palette(petals * 0.4 + uTime * 0.02) * mandala * 0.9;
                }

                if (uLayerEnabled[15] > 0.5) {
                    float lotus = glow(radius - (0.18 + abs(sin(ang * 8.0)) * 0.14 + beat * 0.06), 140.0);
                    float inner = glow(radius - (0.08 + abs(sin(ang * 4.0 + uTime * 0.3)) * 0.06), 180.0);
                    color += vec3(1.0, 0.4, 0.88) * lotus * 0.8;
                    color += vec3(1.0, 0.82, 0.45) * inner * 0.5;
                }

                if (uLayerEnabled[16] > 0.5) {
                    float metatron = 0.0;
                    vec2 n0 = vec2(0.34, 0.0);
                    vec2 n1 = vec2(0.17, 0.2944486);
                    vec2 n2 = vec2(-0.17, 0.2944486);
                    vec2 n3 = vec2(-0.34, 0.0);
                    vec2 n4 = vec2(-0.17, -0.2944486);
                    vec2 n5 = vec2(0.17, -0.2944486);
                    metatron += circleLine(p - n0, 0.18, 70.0) + lineSeg(p, vec2(0.0), n0, 80.0);
                    metatron += circleLine(p - n1, 0.18, 70.0) + lineSeg(p, vec2(0.0), n1, 80.0);
                    metatron += circleLine(p - n2, 0.18, 70.0) + lineSeg(p, vec2(0.0), n2, 80.0);
                    metatron += circleLine(p - n3, 0.18, 70.0) + lineSeg(p, vec2(0.0), n3, 80.0);
                    metatron += circleLine(p - n4, 0.18, 70.0) + lineSeg(p, vec2(0.0), n4, 80.0);
                    metatron += circleLine(p - n5, 0.18, 70.0) + lineSeg(p, vec2(0.0), n5, 80.0);
                    metatron += circleLine(p, 0.18, 90.0);
                    color += vec3(1.0, 0.9, 0.68) * metatron * 0.22;
                }

                if (uLayerEnabled[17] > 0.5) {
                    float triA = glow(equiTriangle(p, vec2(0.0), 0.9), 95.0);
                    float triB = glow(equiTriangle(rot(PI / 3.0) * p, vec2(0.0), 0.9), 95.0);
                    float triC = glow(equiTriangle(p, vec2(0.0), 0.55), 120.0);
                    color += vec3(1.0, 0.86, 0.5) * (triA + triB + triC) * 0.36;
                }

                if (uLayerEnabled[18] > 0.5) {
                    float spiral = sin(ang * 4.0 + log(radius + 0.08) * 11.0 - uTime * (1.0 + highs * 2.0));
                    float arm = smoothstep(0.88, 1.0, spiral) * exp(-radius * 0.8);
                    color += vec3(0.86, 0.92, 1.0) * arm * 0.4;
                    color += vec3(1.0, 0.78, 0.46) * exp(-radius * 8.0) * (0.2 + bass * 0.5);
                }

                if (uLayerEnabled[19] > 0.5) {
                    vec2 g = abs(fract(vec2(p.x * 8.0 / max(radius, 0.2), p.y * 8.0 + uTime * 0.6)) - 0.5);
                    float laser = max(glow(g.x, 55.0), glow(g.y, 55.0)) * exp(-radius * 0.5);
                    color += vec3(0.2, 1.0, 0.85) * laser * 0.26;
                }

                if (uLayerEnabled[20] > 0.5) {
                    float rings = glow(fract(radius * 7.0 - uTime * (0.8 + bass * 2.4) - beat * 1.5) - 0.5, 9.0);
                    color += palette(radius * 0.22 + uTime * 0.02) * rings * exp(-radius * 0.7) * 0.45;
                }

                if (uLayerEnabled[21] > 0.5) {
                    for (int i = 0; i < 10; i++) {
                        float fi = float(i);
                        float phase = uTime * (0.6 + fi * 0.03) + fi * 1.7;
                        vec2 c = vec2(sin(phase), cos(phase * 1.2)) * (0.18 + fi * 0.05);
                        color += palette(fi * 0.08 + uTime * 0.03) * exp(-length(p - c) * (18.0 - energy * 6.0)) * 0.11;
                    }
                }

                if (uLayerEnabled[22] > 0.5) {
                    for (int i = 0; i < 6; i++) {
                        float fi = float(i);
                        vec2 c = vec2(
                            sin(uTime * (0.8 + fi * 0.1) + fi * 2.4) * 0.7,
                            cos(uTime * (1.1 + fi * 0.07) + fi * 1.6) * 0.45
                        );
                        float orb = exp(-length(p - c) * 12.0) * (0.4 + beat * 0.5);
                        color += palette(fi * 0.13 + 0.2) * orb * 0.24;
                    }
                }

                if (uLayerEnabled[23] > 0.5) {
                    for (int i = 0; i < 7; i++) {
                        float fi = float(i);
                        vec2 head = vec2(fract(0.18 * fi + uTime * (0.11 + fi * 0.01)) * 2.4 - 1.2, sin(fi * 3.2 + uTime * 0.7) * 0.6);
                        float streak = exp(-length(p - head) * 18.0);
                        streak += lineSeg(p, head + vec2(-0.18, 0.0), head + vec2(0.18, 0.0), 26.0) * 0.7;
                        color += vec3(1.0, 0.82, 0.58) * streak * 0.18;
                    }
                }

                if (uLayerEnabled[24] > 0.5) {
                    float shards = smoothstep(0.9, 1.0, sin(ang * 14.0 + radius * 9.0 - uTime * 1.4));
                    color += palette(ang / TAU + 0.3) * shards * exp(-radius * 0.6) * 0.32;
                }

                if (uLayerEnabled[25] > 0.5) {
                    for (int i = 0; i < 8; i++) {
                        float fi = float(i);
                        float pr = 0.22 + fi * 0.06 + sin(uTime * 0.4 + fi) * 0.02;
                        vec2 c = vec2(cos(uTime * (0.5 + fi * 0.02) + fi), sin(uTime * (0.7 + fi * 0.03) + fi)) * pr;
                        float depth = 0.5 + 0.5 * sin(uTime + fi * 1.7);
                        color += palette(fi * 0.07 + depth * 0.2) * exp(-length(p - c) * (24.0 - depth * 10.0)) * 0.12;
                    }
                }

                if (uLayerEnabled[26] > 0.5) {
                    float ribbonA = glow(p.y - sin(p.x * 5.0 + uTime * 1.3) * (0.18 + mids * 0.2), 48.0);
                    float ribbonB = glow(p.y - cos(p.x * 4.2 - uTime * 1.0) * (0.28 + highs * 0.14), 56.0);
                    color += vec3(0.18, 0.86, 1.0) * ribbonA * 0.45;
                    color += vec3(1.0, 0.35, 0.8) * ribbonB * 0.36;
                }

                if (uLayerEnabled[27] > 0.5) {
                    vec2 rainUv = vec2(uv.x * 18.0, uv.y * 2.4 + uTime * (2.0 + highs * 4.0));
                    float column = hash21(vec2(floor(rainUv.x), 1.0));
                    float rain = glow(fract(rainUv.y + column * 8.0) - 0.5, 18.0) * smoothstep(0.45, 0.5, abs(fract(rainUv.x) - 0.5));
                    color += vec3(0.0, 0.92, 1.0) * rain * 0.28;
                }

                if (uLayerEnabled[28] > 0.5) {
                    for (int i = 0; i < 8; i++) {
                        float fi = float(i);
                        vec2 c = vec2(sin(fi * 2.2 + uTime * 0.5) * 0.55, -0.9 + fract(uTime * (0.08 + fi * 0.01) + fi * 0.17) * 2.2);
                        float bubble = glow(length(p - c) - (0.05 + bass * 0.05), 120.0);
                        color += vec3(0.36, 0.82, 1.0) * bubble * (0.18 + bass * 0.3);
                    }
                }

                color = pow(max(color, vec3(0.0)), vec3(0.92));
                fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
            }
        """

        private const val POST_VERTEX_SHADER = """
            #version 300 es
            precision mediump float;
            in vec2 aPosition;
            out vec2 vUv;
            void main() {
                vUv = aPosition * 0.5 + 0.5;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        private const val POST_FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            in vec2 vUv;
            out vec4 fragColor;

            uniform sampler2D uSceneTex;
            uniform vec2 uResolution;
            uniform vec4 uAudio;
            uniform vec4 uPulse;
            uniform float uTime;
            uniform float uFxIntensity;
            uniform float uColorMode;
            uniform float uSpeed;
            uniform float uTileCount;
            uniform float uSymmetry;
            uniform float uEffectEnabled[25];

            const float PI = 3.141592653589793;
            const float TAU = 6.283185307179586;

            mat2 rot(float a) {
                float c = cos(a);
                float s = sin(a);
                return mat2(c, -s, s, c);
            }

            vec3 sampleScene(vec2 uv) {
                return texture(uSceneTex, clamp(uv, 0.0, 1.0)).rgb;
            }

            vec3 palette(float t) {
                vec3 a = vec3(0.55, 0.50, 0.52);
                vec3 b = vec3(0.45, 0.28, 0.40);
                vec3 c = vec3(1.00, 0.85, 0.65);
                vec3 d = vec3(0.05, 0.18, 0.33);
                return a + b * cos(TAU * (c * t + d));
            }

            vec3 toneMap(float lum, vec3 a, vec3 b) {
                return mix(a, b, smoothstep(0.02, 0.98, lum));
            }

            vec3 applyColorMode(vec3 color, float mode, float radius, float ang, float time) {
                float lum = clamp(dot(color, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
                vec3 gray = vec3(pow(lum, 0.95));
                float mask = smoothstep(0.015, 0.12, lum);
                if (mask <= 0.0) return color;

                vec3 tinted;
                if (mode < 0.5) tinted = toneMap(lum, vec3(0.02, 0.08, 0.22), vec3(0.45, 0.78, 1.0));
                else if (mode < 1.5) tinted = toneMap(lum, vec3(0.02, 0.16, 0.08), vec3(0.58, 1.0, 0.42));
                else if (mode < 2.5) tinted = toneMap(lum, vec3(0.26, 0.16, 0.02), vec3(1.0, 0.9, 0.3));
                else if (mode < 3.5) tinted = toneMap(lum, vec3(0.22, 0.03, 0.03), vec3(1.0, 0.34, 0.26));
                else if (mode < 4.5) tinted = toneMap(lum, vec3(0.12, 0.03, 0.18), vec3(0.88, 0.4, 1.0));
                else if (mode < 5.5) {
                    float cycle = mod(time * 0.22, 4.0);
                    vec3 colorA = cycle < 1.0 ? vec3(0.0, 0.92, 1.0)
                        : cycle < 2.0 ? vec3(1.0, 0.82, 0.24)
                        : cycle < 3.0 ? vec3(0.9, 0.28, 1.0)
                        : vec3(1.0, 0.28, 0.36);
                    vec3 colorB = cycle < 1.0 ? vec3(1.0, 0.24, 0.72)
                        : cycle < 2.0 ? vec3(0.18, 1.0, 0.52)
                        : cycle < 3.0 ? vec3(0.16, 0.82, 1.0)
                        : vec3(1.0, 0.94, 0.32);
                    float split = smoothstep(0.15, 0.9, lum + sin(time * 0.8 + radius * 8.0 + ang * 2.0) * 0.08);
                    tinted = mix(colorA, colorB, split) * (0.3 + lum * 0.95);
                } else if (mode < 6.5) {
                    vec3 rainbow = palette(lum * 0.9 + radius * 0.2 + ang / TAU * 0.3 - time * 0.04);
                    tinted = mix(color, rainbow * (0.25 + lum * 0.8), 0.42);
                } else {
                    tinted = gray * 1.15;
                }
                return mix(color, tinted, mask);
            }

            float hash21(vec2 p) {
                p = fract(p * vec2(123.34, 345.45));
                p += dot(p, p + 34.345);
                return fract(p.x * p.y);
            }

            void main() {
                float bass = uAudio.x;
                float highs = uAudio.z;
                float energy = uAudio.w;
                float beat = uPulse.x;
                float onset = uPulse.y;
                float nitrousKick = 0.0;
                float coverScale = 1.0;
                float fxSpeed = mix(0.45, 2.8, uSpeed);
                float bounceLift = 0.0;
                float tunnelPhase = 0.0;
                float tunnelScale = 1.0;

                vec2 q = vUv - 0.5;
                q.x *= uResolution.x / max(uResolution.y, 1.0);

                vec2 drift = vec2(0.0);
                if (uEffectEnabled[1] > 0.5) {
                    drift += vec2(sin(uTime * 33.0 * fxSpeed), cos(uTime * 29.0 * fxSpeed)) * (0.015 + bass * 0.04) * uFxIntensity;
                }
                if (uEffectEnabled[3] > 0.5) {
                    drift += vec2(sin(uTime * 0.7 * fxSpeed), cos(uTime * 0.9 * fxSpeed)) * (0.05 + energy * 0.08) * uFxIntensity;
                }

                float zoom = 1.0;
                if (uEffectEnabled[0] > 0.5) {
                    zoom += (bass * 0.24 + energy * 0.16 + beat * 0.12) * uFxIntensity;
                }
                if (uEffectEnabled[18] > 0.5) {
                    nitrousKick = clamp(pow(bass * 0.95 + beat * 1.75 + onset * 0.85, 1.18), 0.0, 2.8);
                    zoom += nitrousKick * (2.3 + uFxIntensity * 3.8);
                    vec2 nitrousDir = length(q) > 0.0001 ? normalize(q) : vec2(0.0);
                    q += nitrousDir * nitrousKick * 0.18 * (1.0 + uFxIntensity);
                }
                if (uEffectEnabled[23] > 0.5) {
                    float bouncePulse = clamp(pow(bass * 0.72 + beat * 1.55 + onset * 0.65, 1.22), 0.0, 2.0);
                    bounceLift = bouncePulse * (0.08 + uFxIntensity * 0.16) - uPulse.w * (0.03 + uFxIntensity * 0.04);
                    zoom += bouncePulse * (0.04 + uFxIntensity * 0.06);
                }
                if (uEffectEnabled[24] > 0.5) {
                    tunnelPhase = fract(uTime * (0.22 + beat * 0.05 + uFxIntensity * 0.08) * fxSpeed);
                    tunnelScale = exp2(tunnelPhase * (1.5 + uFxIntensity * 1.9));
                    zoom += 0.12 + uFxIntensity * 0.18;
                }

                float angle = 0.0;
                if (uEffectEnabled[2] > 0.5) {
                    angle += (sin(uTime * 1.1 * fxSpeed) * 0.14 + bass * 0.26 + beat * 0.14) * uFxIntensity;
                }
                if (uEffectEnabled[19] > 0.5) {
                    angle += (uTime * 2.9 * fxSpeed + energy * 0.35) * (0.9 + uFxIntensity * 0.95);
                }
                if (uEffectEnabled[24] > 0.5) {
                    angle += tunnelPhase * (0.16 + uFxIntensity * 0.28) + length(q) * 0.22;
                }

                q = rot(angle) * q;
                q.y += bounceLift;
                q /= zoom;
                q += drift;

                if (uEffectEnabled[13] > 0.5) {
                    float seg = max(4.0, uSymmetry);
                    float a = atan(q.y, q.x);
                    float r = length(q);
                    float stepA = TAU / seg;
                    a = mod(a, stepA);
                    a = abs(a - stepA * 0.5);
                    q = vec2(cos(a), sin(a)) * r;
                }

                if (uEffectEnabled[5] > 0.5) q.x = abs(q.x);
                if (uEffectEnabled[6] > 0.5) q.y = abs(q.y);

                if (uEffectEnabled[1] > 0.5) coverScale = min(coverScale, 0.72);
                if (uEffectEnabled[3] > 0.5) coverScale = min(coverScale, 0.72);
                if (uEffectEnabled[2] > 0.5) coverScale = min(coverScale, 0.72);
                if (uEffectEnabled[7] > 0.5) coverScale = min(coverScale, 0.74);
                if (uEffectEnabled[13] > 0.5) coverScale = min(coverScale, 0.78);
                if (uEffectEnabled[18] > 0.5) coverScale = min(coverScale, 0.64);
                if (uEffectEnabled[19] > 0.5) coverScale = min(coverScale, 0.56);
                if (uEffectEnabled[23] > 0.5) coverScale = min(coverScale, 0.68);
                if (uEffectEnabled[24] > 0.5) coverScale = min(coverScale, 0.5);
                q *= coverScale;

                vec2 sampleUv = vec2(
                    q.x / (uResolution.x / max(uResolution.y, 1.0)),
                    q.y
                ) * 0.5 + 0.5;

                if (uEffectEnabled[7] > 0.5) {
                    sampleUv = fract(sampleUv * max(2.0, uTileCount));
                }

                if (uEffectEnabled[8] > 0.5) {
                    float px = mix(240.0, 84.0, clamp(uFxIntensity, 0.0, 1.0));
                    sampleUv = floor(sampleUv * px) / px;
                }

                if (uEffectEnabled[24] > 0.5) {
                    sampleUv = fract((sampleUv - 0.5) * tunnelScale + 0.5);
                }

                vec3 color;
                if (uEffectEnabled[12] > 0.5) {
                    float split = (0.003 + highs * 0.012 + length(q) * 0.008) * (0.7 + uFxIntensity);
                    vec3 r = sampleScene(sampleUv + vec2(split, 0.0));
                    vec3 g = sampleScene(sampleUv);
                    vec3 b = sampleScene(sampleUv - vec2(split, 0.0));
                    color = vec3(r.r, g.g, b.b);
                } else {
                    color = sampleScene(sampleUv);
                }

                if (uEffectEnabled[18] > 0.5) {
                    vec2 centerDir = sampleUv - 0.5;
                    float blurAmount = (0.055 + nitrousKick * 0.11) * (1.0 + uFxIntensity);
                    vec3 nitrousBlur = vec3(0.0);
                    nitrousBlur += sampleScene(sampleUv - centerDir * blurAmount * 0.7);
                    nitrousBlur += sampleScene(sampleUv - centerDir * blurAmount * 0.35);
                    nitrousBlur += sampleScene(sampleUv);
                    nitrousBlur += sampleScene(sampleUv + centerDir * blurAmount * 0.3);
                    nitrousBlur += sampleScene(sampleUv + centerDir * blurAmount * 0.65);
                    nitrousBlur += sampleScene(sampleUv + centerDir * blurAmount * 1.0);
                    nitrousBlur += sampleScene(sampleUv + centerDir * blurAmount * 1.35);
                    nitrousBlur *= 1.0 / 7.0;
                    float smear = min(0.92, nitrousKick * 0.58 + beat * 0.22);
                    float shock = max(0.0, sin(uTime * 34.0 + beat * 2.0)) * beat * 0.24;
                    color = mix(color, nitrousBlur, smear);
                    color *= 1.0 + smear * 0.45 + shock;
                    color = mix(color, pow(color, vec3(0.78)), min(0.32, smear * 0.26 + shock));
                }

                if (uEffectEnabled[14] > 0.5) {
                    vec2 px = vec2(1.0) / uResolution;
                    vec3 blur = vec3(0.0);
                    blur += sampleScene(sampleUv + px * vec2(3.0, 0.0));
                    blur += sampleScene(sampleUv + px * vec2(-3.0, 0.0));
                    blur += sampleScene(sampleUv + px * vec2(0.0, 3.0));
                    blur += sampleScene(sampleUv + px * vec2(0.0, -3.0));
                    blur += sampleScene(sampleUv + px * vec2(2.0, 2.0));
                    blur += sampleScene(sampleUv + px * vec2(-2.0, 2.0));
                    blur += sampleScene(sampleUv + px * vec2(2.0, -2.0));
                    blur += sampleScene(sampleUv + px * vec2(-2.0, -2.0));
                    blur *= 0.125;
                    color += blur * (0.22 + uFxIntensity * 0.42);
                }

                if (uEffectEnabled[15] > 0.5) {
                    vec3 prism = vec3(
                        sampleScene(sampleUv + vec2(0.005, 0.0)).r,
                        sampleScene(sampleUv + vec2(-0.004, 0.003)).g,
                        sampleScene(sampleUv + vec2(0.0, -0.005)).b
                    );
                    color = mix(color, prism, 0.2 + uFxIntensity * 0.25);
                }

                if (uEffectEnabled[16] > 0.5) {
                    vec2 p = q;
                    float ang = atan(p.y, p.x);
                    float rr = length(p);
                    float rays = pow(max(0.0, cos(ang * (18.0 + bass * 12.0) - uTime * 0.6)), 14.0);
                    color += mix(vec3(1.0, 0.74, 0.3), vec3(1.0, 0.2, 0.72), step(0.0, sin(ang * 7.0))) * rays * exp(-rr * 1.1) * 0.35;
                }

                if (uEffectEnabled[17] > 0.5) {
                    vec2 p = q;
                    float rr = length(p);
                    float ang = atan(p.y, p.x);
                    float circles = exp(-abs(rr - 0.28) * 90.0) + exp(-abs(rr - 0.52) * 70.0);
                    float spokes = exp(-abs(fract((ang + PI) / TAU * max(6.0, uSymmetry)) - 0.5) * 34.0) * exp(-rr * 0.5);
                    color += vec3(1.0, 0.92, 0.72) * (circles + spokes) * 0.12;
                }

                if (uEffectEnabled[20] > 0.5) {
                    float lum = dot(color, vec3(0.2126, 0.7152, 0.0722));
                    float edge = smoothstep(0.02, 0.16, length(vec2(dFdx(lum), dFdy(lum))) * (3.2 + uFxIntensity * 4.5));
                    float pulse = 0.5 + 0.5 * sin(uTime * 9.0 * fxSpeed + atan(q.y, q.x) * 5.0 + length(q) * 18.0);
                    vec3 neon = mix(vec3(0.0, 1.0, 0.92), vec3(1.0, 0.15, 0.78), pulse);
                    color += neon * edge * (0.34 + uFxIntensity * 0.58);
                    color = mix(color, max(color, neon * (0.14 + edge * 0.45)), 0.42);
                }

                if (uEffectEnabled[22] > 0.5) {
                    vec2 centerDir = sampleUv - 0.5;
                    float dirLen = max(length(centerDir), 0.0001);
                    vec2 ray = centerDir / dirLen;
                    float warp = pow(max(0.0, 1.0 - dirLen * 1.8), 2.4);
                    vec3 hyper = vec3(0.0);
                    hyper += sampleScene(sampleUv + ray * 0.01);
                    hyper += sampleScene(sampleUv + ray * 0.025);
                    hyper += sampleScene(sampleUv + ray * 0.05);
                    hyper += sampleScene(sampleUv + ray * 0.085);
                    hyper += sampleScene(sampleUv + ray * 0.13);
                    hyper *= 0.2;
                    float lanes = pow(max(0.0, cos(atan(centerDir.y, centerDir.x) * (46.0 + highs * 26.0) - uTime * 8.0 * fxSpeed)), 28.0);
                    float streak = lanes * warp * (0.28 + uFxIntensity * 0.42);
                    color += hyper * streak * 1.6;
                    color += vec3(0.78, 0.94, 1.0) * streak * 0.3;
                }

                if (uEffectEnabled[9] > 0.5) {
                    float scan = 0.92 + 0.08 * sin((vUv.y + uTime * 0.02) * uResolution.y * 0.6);
                    color *= scan;
                }

                if (uEffectEnabled[10] > 0.5) {
                    float vignette = smoothstep(1.18, 0.28, length(q));
                    color *= mix(1.0, vignette, 0.6 + bass * 0.2);
                }

                if (uEffectEnabled[11] > 0.5) {
                    float strobe = max(0.0, sin(uTime * 18.0 * fxSpeed)) * highs * (0.12 + uFxIntensity * 0.22);
                    color = mix(color, vec3(1.0), strobe);
                }

                if (uEffectEnabled[4] > 0.5) {
                    color = vec3(1.0) - color;
                }

                if (uEffectEnabled[21] > 0.5) {
                    float lum = dot(color, vec3(0.2126, 0.7152, 0.0722));
                    float edge = smoothstep(0.018, 0.14, length(vec2(dFdx(lum), dFdy(lum))) * (3.4 + uFxIntensity * 5.0));
                    vec3 xray = mix(vec3(0.1, 0.92, 1.0), vec3(1.0, 0.28, 0.8), 0.5 + 0.5 * sin(uTime * 3.0 * fxSpeed + atan(q.y, q.x) * 3.0));
                    color = xray * edge;
                }

                color = applyColorMode(color, uColorMode, length(q), atan(q.y, q.x), uTime);
                float dust = pow(hash21(floor(vUv * uResolution * 0.5) + floor(uTime * 24.0)), 24.0);
                color += vec3(1.0) * dust * onset * 0.08;
                fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
            }
        """
    }
}
