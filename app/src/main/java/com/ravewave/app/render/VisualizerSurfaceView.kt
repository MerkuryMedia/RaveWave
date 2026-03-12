package com.ravewave.app.render

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.ravewave.app.analyzer.AnalyzerMetrics
import com.ravewave.app.analyzer.AudioAnalyzerEngine
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
        sceneULayerEnabled = GLES30.glGetUniformLocation(sceneProgram, "uLayerEnabled")
        sceneUFftTex = GLES30.glGetUniformLocation(sceneProgram, "uFftTex")
        sceneUWaveTex = GLES30.glGetUniformLocation(sceneProgram, "uWaveTex")

        postUTime = GLES30.glGetUniformLocation(postProgram, "uTime")
        postUResolution = GLES30.glGetUniformLocation(postProgram, "uResolution")
        postUFxIntensity = GLES30.glGetUniformLocation(postProgram, "uFxIntensity")
        postUTileCount = GLES30.glGetUniformLocation(postProgram, "uTileCount")
        postUSymmetry = GLES30.glGetUniformLocation(postProgram, "uSymmetry")
        postUAudio = GLES30.glGetUniformLocation(postProgram, "uAudio")
        postUPulse = GLES30.glGetUniformLocation(postProgram, "uPulse")
        postUEffectEnabled = GLES30.glGetUniformLocation(postProgram, "uEffectEnabled")
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
        val metrics = analyzerEngine.metrics.value
        val time = ((System.nanoTime() - startNanos) / 1_000_000_000.0).toFloat()

        analyzerEngine.snapshotBins(fftSamples)
        analyzerEngine.snapshotWaveform(waveSamples)
        uploadAudioTextures()

        mapLayerUniforms(state)
        mapEffectUniforms(state)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo.framebufferId)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        drawScenePass(state, metrics, time)

        particles.update(state, metrics, time)
        particles.draw()

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        drawPostPass(state, metrics, time)
    }

    private fun drawScenePass(state: SceneState, metrics: AnalyzerMetrics, time: Float) {
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
        GLES30.glUniform2f(
            sceneUPulse,
            metrics.beatPulse,
            metrics.onsetPulse
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
        GLES30.glUniform1f(postUTileCount, state.tileCount.toFloat())
        GLES30.glUniform1f(postUSymmetry, state.symmetrySegments.toFloat())
        GLES30.glUniform4f(
            postUAudio,
            metrics.bass,
            metrics.mids,
            metrics.highs,
            metrics.energy
        )
        GLES30.glUniform2f(
            postUPulse,
            metrics.beatPulse,
            metrics.onsetPulse
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

    private fun mapLayerUniforms(state: SceneState) {
        layerUniforms.fill(0f)
        for (layer in state.enabledLayers) {
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
            uniform vec2 uPulse;
            uniform float uTime;
            uniform float uLayerEnabled[29];

            float fftSample(float x) {
                return texture(uFftTex, vec2(clamp(x, 0.0, 1.0), 0.5)).r;
            }

            float waveSample(float x) {
                return texture(uWaveTex, vec2(clamp(x, 0.0, 1.0), 0.5)).r * 2.0 - 1.0;
            }

            vec3 layerColor(float hue, float sat, float val) {
                vec3 rgb = clamp(abs(mod(hue * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
                rgb = rgb * rgb * (3.0 - 2.0 * rgb);
                return val * mix(vec3(1.0), rgb, sat);
            }

            void main() {
                vec2 uv = vUv;
                vec2 p = (uv - 0.5) * vec2(uResolution.x / uResolution.y, 1.0);
                float r = length(p);
                float a = atan(p.y, p.x);

                float bass = uAudio.x;
                float mids = uAudio.y;
                float highs = uAudio.z;
                float energy = uAudio.w;
                float beat = uPulse.x;
                float onset = uPulse.y;

                vec3 color = vec3(0.0);
                color += vec3(0.01, 0.01, 0.02);

                if (uLayerEnabled[0] > 0.5) {
                    float f = fftSample(uv.x);
                    float bar = smoothstep(0.0, 0.02, f - (1.0 - uv.y));
                    color += layerColor(uv.x * 0.8 + uTime * 0.02, 0.9, bar * 1.2);
                }
                if (uLayerEnabled[1] > 0.5) {
                    float w = waveSample(uv.x);
                    float line = smoothstep(0.018, 0.0, abs(uv.y - (0.5 + w * 0.23)));
                    color += vec3(0.0, 1.0, 0.9) * line;
                }
                if (uLayerEnabled[2] > 0.5) {
                    float spokes = fftSample(fract(a / (6.28318))) * 0.4;
                    float ray = smoothstep(0.004, 0.0, abs(fract((a + 3.1415) * 10.0) - 0.5));
                    color += vec3(1.0, 0.35, 0.1) * ray * (spokes + 0.2);
                }
                if (uLayerEnabled[3] > 0.5) {
                    float meter = smoothstep(0.0, 0.01, energy - (1.0 - uv.y));
                    color += vec3(0.0, 1.0, 0.45) * meter * 0.8;
                }
                if (uLayerEnabled[4] > 0.5) {
                    color += vec3(1.0) * smoothstep(0.02, 0.0, abs(r - (0.2 + bass * 0.2))) * 0.2;
                }
                if (uLayerEnabled[5] > 0.5) {
                    float dna = smoothstep(0.012, 0.0, abs(p.x - sin(p.y * 30.0 + uTime * 2.0) * (0.1 + mids * 0.3)));
                    color += vec3(0.0, 0.8, 1.0) * dna * 0.5;
                }
                if (uLayerEnabled[6] > 0.5) {
                    float liquid = smoothstep(0.02, 0.0, abs(p.y - sin(p.x * 20.0 + uTime * 1.4) * (0.08 + mids * 0.2)));
                    color += vec3(0.0, 0.5, 1.0) * liquid;
                }
                if (uLayerEnabled[7] > 0.5) {
                    float bin = fftSample(fract(uv.x));
                    float city = smoothstep(0.0, 0.01, bin - (1.0 - uv.y));
                    color += vec3(0.0, 1.0, 0.8) * city * 0.8;
                }
                if (uLayerEnabled[8] > 0.5) {
                    float neural = sin((p.x + p.y) * 40.0 + uTime * 3.0) * 0.5 + 0.5;
                    color += vec3(0.2, 0.8, 1.0) * neural * highs * 0.5;
                }
                if (uLayerEnabled[9] > 0.5) {
                    float orbit = smoothstep(0.01, 0.0, abs(r - (0.3 + sin(uTime + a * 6.0) * 0.05 + bass * 0.1)));
                    color += vec3(1.0) * orbit * 0.5;
                }
                if (uLayerEnabled[10] > 0.5) {
                    float hole = smoothstep(0.35 + bass * 0.15, 0.0, r);
                    color += vec3(0.7, 0.7, 0.95) * hole * 0.15;
                }
                if (uLayerEnabled[11] > 0.5) {
                    float crystal = smoothstep(0.006, 0.0, abs(fract(a * 12.0 / 6.28318) - 0.5));
                    color += vec3(0.4, 0.8, 1.0) * crystal * fftSample(fract(a / 6.28318));
                }
                if (uLayerEnabled[12] > 0.5) {
                    float fire = smoothstep(0.0, 0.02, (fftSample(uv.x) * 0.8 + bass * 0.5) - (1.0 - uv.y));
                    color += vec3(1.0, 0.35, 0.0) * fire;
                }
                if (uLayerEnabled[13] > 0.5) {
                    float magnetic = smoothstep(0.01, 0.0, abs(sin(r * 70.0 - uTime * 4.0)));
                    color += vec3(0.0, 1.0, 0.8) * magnetic * 0.3;
                }
                if (uLayerEnabled[14] > 0.5) {
                    float mandala = smoothstep(0.01, 0.0, abs(sin(a * 12.0 + r * 28.0 - uTime * 1.2)));
                    color += vec3(1.0, 0.8, 0.5) * mandala * (0.25 + highs * 0.45);
                }
                if (uLayerEnabled[15] > 0.5) {
                    float lotus = smoothstep(0.02, 0.0, abs(r - (0.25 + sin(a * 9.0 + uTime) * 0.12 + bass * 0.15)));
                    color += vec3(1.0, 0.4, 0.85) * lotus * 0.5;
                }
                if (uLayerEnabled[16] > 0.5) {
                    float grid = smoothstep(0.01, 0.0, abs(fract(p.x * 10.0) - 0.5)) + smoothstep(0.01, 0.0, abs(fract(p.y * 10.0) - 0.5));
                    color += vec3(1.0, 0.85, 0.6) * grid * 0.22;
                }
                if (uLayerEnabled[17] > 0.5) {
                    float tri = smoothstep(0.01, 0.0, abs(sin(a * 3.0 + uTime * 0.8) - r * 1.4));
                    color += vec3(1.0, 0.85, 0.45) * tri * 0.45;
                }
                if (uLayerEnabled[18] > 0.5) {
                    float spiral = smoothstep(0.01, 0.0, abs(fract((a + r * 10.0 + uTime * 0.7) * 0.5) - 0.5));
                    color += vec3(0.9, 0.9, 1.0) * spiral * 0.3;
                }
                if (uLayerEnabled[19] > 0.5) {
                    float laser = smoothstep(0.004, 0.0, abs(fract(uv.y * 80.0 + uTime * 2.0) - 0.5));
                    color += layerColor(uv.y * 0.6, 1.0, laser * 0.7);
                }
                if (uLayerEnabled[20] > 0.5) {
                    float rings = smoothstep(0.008, 0.0, abs(fract(r * 18.0 - bass * 8.0 - uTime) - 0.5));
                    color += layerColor(0.55 + r * 0.3, 0.9, rings * 0.7);
                }

                float musicObjects = 0.0;
                for (int i = 21; i <= 28; ++i) {
                    musicObjects += uLayerEnabled[i];
                }
                if (musicObjects > 0.5) {
                    float aura = smoothstep(0.9, 0.0, r) * (0.06 + beat * 0.2);
                    color += vec3(0.2, 0.6, 1.0) * aura;
                }

                color += vec3(0.03, 0.02, 0.04) * onset;
                fragColor = vec4(color, 1.0);
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
            uniform vec2 uPulse;
            uniform float uTime;
            uniform float uFxIntensity;
            uniform float uTileCount;
            uniform float uSymmetry;
            uniform float uEffectEnabled[18];

            mat2 rot(float a) {
                float c = cos(a);
                float s = sin(a);
                return mat2(c, -s, s, c);
            }

            vec3 sampleScene(vec2 uv) {
                return texture(uSceneTex, clamp(uv, 0.0, 1.0)).rgb;
            }

            void main() {
                float bass = uAudio.x;
                float mids = uAudio.y;
                float highs = uAudio.z;
                float energy = uAudio.w;
                float beat = uPulse.x;
                float onset = uPulse.y;

                vec2 uv = vUv - 0.5;

                float zoom = 1.0;
                if (uEffectEnabled[0] > 0.5) {
                    zoom += (bass * 0.18 + energy * 0.08) * uFxIntensity;
                }

                vec2 shake = vec2(0.0);
                if (uEffectEnabled[1] > 0.5) {
                    float amount = (0.02 + bass * 0.09 + mids * 0.04) * uFxIntensity;
                    shake = vec2(sin(uTime * 37.0), cos(uTime * 29.0)) * amount;
                }

                float angle = 0.0;
                if (uEffectEnabled[2] > 0.5) {
                    angle += sin(uTime * 1.7) * 0.08 * uFxIntensity + bass * 0.12 * uFxIntensity;
                }

                vec2 drift = vec2(0.0);
                if (uEffectEnabled[3] > 0.5) {
                    drift = vec2(sin(uTime * 0.8), cos(uTime * 0.7)) * 0.06 * uFxIntensity;
                }

                uv = rot(angle) * uv;
                uv /= zoom;
                uv += drift + shake;

                if (uEffectEnabled[5] > 0.5) uv.x = abs(uv.x);
                if (uEffectEnabled[6] > 0.5) uv.y = abs(uv.y);

                vec2 sampleUv = uv + 0.5;

                if (uEffectEnabled[13] > 0.5) {
                    vec2 p = sampleUv - 0.5;
                    float seg = max(4.0, uSymmetry);
                    float stepA = 6.28318 / seg;
                    float ra = length(p);
                    float an = atan(p.y, p.x);
                    an = mod(an, stepA);
                    an = abs(an - stepA * 0.5);
                    sampleUv = vec2(cos(an), sin(an)) * ra + 0.5;
                }

                if (uEffectEnabled[7] > 0.5) {
                    sampleUv = fract(sampleUv * max(2.0, uTileCount));
                }

                if (uEffectEnabled[8] > 0.5) {
                    float px = mix(120.0, 380.0, 1.0 - uFxIntensity);
                    sampleUv = floor(sampleUv * px) / px;
                }

                vec3 color;
                if (uEffectEnabled[12] > 0.5) {
                    float shift = (0.003 + uFxIntensity * 0.015) * (1.0 + highs);
                    vec3 r = sampleScene(sampleUv + vec2(shift, 0.0));
                    vec3 g = sampleScene(sampleUv);
                    vec3 b = sampleScene(sampleUv - vec2(shift, 0.0));
                    color = vec3(r.r, g.g, b.b);
                } else {
                    color = sampleScene(sampleUv);
                }

                if (uEffectEnabled[14] > 0.5) {
                    vec2 px = vec2(1.0) / uResolution;
                    vec3 blur = vec3(0.0);
                    blur += sampleScene(sampleUv + px * vec2(2.0, 0.0));
                    blur += sampleScene(sampleUv + px * vec2(-2.0, 0.0));
                    blur += sampleScene(sampleUv + px * vec2(0.0, 2.0));
                    blur += sampleScene(sampleUv + px * vec2(0.0, -2.0));
                    blur *= 0.25;
                    color += blur * (0.18 + uFxIntensity * 0.38);
                }

                if (uEffectEnabled[15] > 0.5) {
                    vec3 prism = vec3(
                        sampleScene(sampleUv + vec2(0.004, 0.0)).r,
                        sampleScene(sampleUv + vec2(-0.003, 0.002)).g,
                        sampleScene(sampleUv + vec2(0.0, -0.004)).b
                    );
                    color = mix(color, prism, 0.24 + uFxIntensity * 0.22);
                }

                if (uEffectEnabled[4] > 0.5) color = vec3(1.0) - color;

                if (uEffectEnabled[9] > 0.5) {
                    float scan = 0.9 + 0.1 * sin((vUv.y + uTime * 0.04) * uResolution.y * 0.9);
                    color *= scan;
                }

                if (uEffectEnabled[10] > 0.5) {
                    float vignette = smoothstep(0.95, 0.22, distance(vUv, vec2(0.5)));
                    color *= mix(1.0, vignette, 0.55 + bass * 0.25 * uFxIntensity);
                }

                if (uEffectEnabled[11] > 0.5) {
                    float strobe = (sin(uTime * 18.0) * 0.5 + 0.5) * highs * uFxIntensity;
                    color += vec3(strobe * 0.45);
                }

                if (uEffectEnabled[16] > 0.5) {
                    vec2 p = vUv - 0.5;
                    float rr = length(p);
                    float rays = smoothstep(0.0, 0.008, abs(fract(atan(p.y, p.x) * 10.0 + uTime * 0.8) - 0.5));
                    color += vec3(1.0, 0.7, 0.35) * rays * (0.04 + bass * 0.2) * (1.0 - rr);
                }

                if (uEffectEnabled[17] > 0.5) {
                    vec2 g = abs(fract((vUv - 0.5) * 12.0) - 0.5);
                    float grid = smoothstep(0.49, 0.47, min(g.x, g.y));
                    color += vec3(1.0, 0.9, 0.6) * grid * 0.12;
                }

                color += vec3(0.02, 0.02, 0.05) * onset;
                fragColor = vec4(color, 1.0);
            }
        """
    }
}
