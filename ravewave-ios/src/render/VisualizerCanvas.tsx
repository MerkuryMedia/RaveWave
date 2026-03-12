import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { LayoutChangeEvent, StyleSheet, View } from 'react-native';
import { ExpoWebGLRenderingContext, GLView } from 'expo-gl';
import { AnalyzerFrame } from '../analyzer/AnalyzerEngine';
import { SceneState } from '../scene/sceneState';

type Props = {
  frame: AnalyzerFrame;
  scene: SceneState;
};

type GLResources = {
  program: WebGLProgram;
  quadBuffer: WebGLBuffer;
  posLoc: number;
  uResolution: WebGLUniformLocation | null;
  uTime: WebGLUniformLocation | null;
  uAudioTex: WebGLUniformLocation | null;
  uLevels: WebGLUniformLocation | null;
  uFxIntensity: WebGLUniformLocation | null;
  uTileCount: WebGLUniformLocation | null;
  uSymmetry: WebGLUniformLocation | null;
  uLayer: WebGLUniformLocation | null;
  uLayer2: WebGLUniformLocation | null;
  uFxA: WebGLUniformLocation | null;
  uFxB: WebGLUniformLocation | null;
  uFxC: WebGLUniformLocation | null;
  uFxD: WebGLUniformLocation | null;
  uFxE: WebGLUniformLocation | null;
  audioTex: WebGLTexture;
  audioBytes: Uint8Array;
};

const AUDIO_TEX_WIDTH = 64;

const VERT_SRC = `
attribute vec2 aPosition;
varying vec2 vUv;
void main() {
  vUv = (aPosition + 1.0) * 0.5;
  gl_Position = vec4(aPosition, 0.0, 1.0);
}
`;

const FRAG_SRC = `
precision mediump float;
varying vec2 vUv;

uniform vec2 uResolution;
uniform float uTime;
uniform sampler2D uAudioTex;
uniform vec4 uLevels;      // bass, mids, highs, energy
uniform float uFxIntensity;
uniform float uTileCount;
uniform float uSymmetry;
uniform vec4 uLayer;       // spectrum, waveform, radial, objects
uniform vec4 uLayer2;      // rings, laser, sacred, vu
uniform vec4 uFxA;         // zoom, shake, rotate, drift
uniform vec4 uFxB;         // invert, mirrorX, mirrorY, tile
uniform vec4 uFxC;         // pixelate, scanlines, vignette, strobe
uniform vec4 uFxD;         // rgbsplit, kaleido, bloom, prism
uniform vec2 uFxE;         // sunburst, sacredgrid

const float PI = 3.141592653589793;
const float TAU = 6.283185307179586;

float audioAt(float x) {
  return texture2D(uAudioTex, vec2(clamp(x, 0.0, 1.0), 0.5)).r;
}

vec3 palette(float t) {
  vec3 a = vec3(0.45, 0.48, 0.55);
  vec3 b = vec3(0.40, 0.35, 0.30);
  vec3 c = vec3(1.00, 0.95, 0.90);
  vec3 d = vec3(0.23, 0.37, 0.53);
  return a + b * cos(TAU * (c * t + d));
}

mat2 rot(float a) {
  float s = sin(a);
  float c = cos(a);
  return mat2(c, -s, s, c);
}

float lineGlow(float d, float width) {
  return exp(-abs(d) * width);
}

void main() {
  vec2 p = vUv;

  if (uFxB.w > 0.5) {
    float tc = max(2.0, uTileCount);
    p = fract(p * tc);
  }

  if (uFxC.x > 0.5) {
    float px = mix(220.0, 84.0, clamp(uFxIntensity, 0.0, 1.0));
    p = floor(p * px) / px;
  }

  vec2 q = p * 2.0 - 1.0;
  float shakeAmp = (0.004 + uLevels.x * 0.02) * uFxA.y * uFxIntensity;
  q += vec2(sin(uTime * 31.0), cos(uTime * 27.0)) * shakeAmp;
  q += vec2(sin(uTime * 0.8), cos(uTime * 0.7)) * (0.02 * uFxA.w * uFxIntensity);

  float zoom = 1.0 + (uLevels.x * 0.25 + uLevels.w * 0.12) * uFxA.x * uFxIntensity;
  q /= max(zoom, 0.001);

  float rAngle = (sin(uTime * 1.7) * 0.16 + uLevels.x * 0.24) * uFxA.z * uFxIntensity;
  q = rot(rAngle) * q;

  if (uFxD.y > 0.5) {
    float seg = max(4.0, uSymmetry);
    float a = atan(q.y, q.x);
    float rr = length(q);
    float stepA = TAU / seg;
    a = mod(a, stepA);
    a = abs(a - stepA * 0.5);
    q = vec2(cos(a), sin(a)) * rr;
  }

  if (uFxB.y > 0.5) q.x = abs(q.x);
  if (uFxB.z > 0.5) q.y = abs(q.y);

  p = q * 0.5 + 0.5;
  vec2 uv = clamp(p, 0.0, 1.0);

  vec3 col = mix(vec3(0.02, 0.02, 0.03), vec3(0.04, 0.10, 0.18), uv.y);
  col += palette(uv.x * 0.35 + uv.y * 0.12 + uTime * 0.03) * 0.06;

  if (uLayer.x > 0.5) {
    for (int i = 0; i < 32; i++) {
      float fi = float(i);
      float x0 = fi / 32.0;
      float x1 = (fi + 1.0) / 32.0;
      float a = audioAt((fi + 0.5) / 32.0);
      float h = 0.06 + a * 0.62;
      float inBar = step(x0, uv.x) * step(uv.x, x1) * step(1.0 - h, uv.y);
      vec3 barCol = palette(fi / 32.0 + a * 0.2 + uTime * 0.04);
      col += barCol * inBar * 0.95;
    }
  }

  if (uLayer.y > 0.5) {
    float wy = 0.5 + (audioAt(uv.x) - 0.5) * 0.76;
    float w = lineGlow(uv.y - wy, 240.0);
    col += vec3(0.08, 0.95, 0.86) * w;
  }

  if (uLayer.z > 0.5) {
    float a = atan(q.y, q.x);
    float an = (a + PI) / TAU;
    float rr = length(q);
    float ar = audioAt(an);
    float rTarget = 0.2 + ar * 0.62 + uLevels.x * 0.16;
    float radial = lineGlow(rr - rTarget, 95.0);
    col += vec3(0.78, 0.68, 1.0) * radial;
  }

  if (uLayer2.x > 0.5) {
    float rr = length(q);
    float pulse = sin(rr * 42.0 - uTime * (2.8 + uLevels.x * 6.0));
    float rings = smoothstep(0.94, 1.0, pulse) * (0.2 + uLevels.w * 0.4);
    col += vec3(0.42, 0.78, 1.0) * rings;
  }

  if (uLayer.w > 0.5) {
    for (int i = 0; i < 14; i++) {
      float fi = float(i);
      float ax = audioAt(fi / 14.0);
      vec2 c = vec2(
        0.5 + 0.42 * sin(uTime * (0.4 + fi * 0.03) + fi * 1.31),
        0.5 + 0.42 * cos(uTime * (0.5 + fi * 0.04) + fi * 1.73)
      );
      float d = length(uv - c);
      float orb = exp(-d * (120.0 - ax * 60.0));
      col += palette(fi * 0.05 + ax * 0.4) * orb * (0.35 + uLevels.x * 0.35);
    }
  }

  if (uLayer2.y > 0.5) {
    float gy = abs(fract(uv.y * 28.0 + uTime * 0.15) - 0.5);
    float grid = lineGlow(gy, 90.0) * (0.16 + audioAt(uv.y) * 0.6);
    col += vec3(0.2, 0.95, 0.82) * grid;
  }

  if (uLayer2.z > 0.5) {
    float rr = length(q);
    float ag = abs(fract((atan(q.y, q.x) + PI) / TAU * max(6.0, uSymmetry)) - 0.5);
    float sacred = lineGlow(ag, 34.0) * lineGlow(rr - (0.28 + uLevels.x * 0.18), 70.0);
    col += vec3(1.0, 0.86, 0.55) * sacred * 1.2;
  }

  if (uLayer2.w > 0.5) {
    float vuH = 0.1 + uLevels.w * 0.8;
    float vu = step(0.46, uv.x) * step(uv.x, 0.54) * step(1.0 - vuH, uv.y);
    col += vec3(0.18, 1.0, 0.52) * vu;
  }

  if (uFxD.x > 0.5) {
    float shift = (0.004 + uLevels.z * 0.02) * uFxIntensity;
    float ar = audioAt(clamp(uv.x + shift, 0.0, 1.0));
    float ab = audioAt(clamp(uv.x - shift, 0.0, 1.0));
    col.r += ar * 0.25;
    col.b += ab * 0.25;
  }

  if (uFxD.w > 0.5) {
    vec3 prism = vec3(col.b, col.r, col.g);
    col = mix(col, prism, 0.18 + 0.22 * uFxIntensity);
  }

  if (uFxD.z > 0.5) {
    float glow = exp(-length(q) * 3.2) * (0.25 + uLevels.w * 0.7);
    col += vec3(0.3, 0.7, 1.0) * glow * uFxIntensity;
  }

  if (uFxE.x > 0.5) {
    float a = atan(q.y, q.x);
    float rays = abs(sin(a * 28.0 + uTime * 0.8));
    float sun = smoothstep(0.86, 1.0, rays) * exp(-length(q) * 1.7);
    col += mix(vec3(1.0, 0.74, 0.38), vec3(1.0, 0.3, 0.75), step(0.5, rays)) * sun * 0.6;
  }

  if (uFxE.y > 0.5) {
    vec2 g = abs(fract(uv * vec2(8.0, 6.0)) - 0.5);
    float lines = max(lineGlow(g.x, 52.0), lineGlow(g.y, 52.0));
    col += vec3(1.0, 0.92, 0.7) * lines * 0.11;
  }

  if (uFxC.y > 0.5) {
    float scan = smoothstep(0.0, 1.0, sin(uv.y * uResolution.y * 0.5) * 0.5 + 0.5);
    col *= mix(0.85, 1.0, scan);
  }

  if (uFxC.z > 0.5) {
    float vig = smoothstep(1.05, 0.32, length(q));
    col *= vig;
  }

  if (uFxC.w > 0.5) {
    float pulse = max(0.0, sin(uTime * 18.0));
    col = mix(col, vec3(1.0), pulse * uLevels.z * uFxIntensity * 0.25);
  }

  if (uFxB.x > 0.5) {
    col = vec3(1.0) - col;
  }

  gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
`;

const compileShader = (
  gl: ExpoWebGLRenderingContext,
  type: number,
  source: string
): WebGLShader => {
  const shader = gl.createShader(type);
  if (!shader) throw new Error('Unable to create shader.');
  gl.shaderSource(shader, source);
  gl.compileShader(shader);
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    const log = gl.getShaderInfoLog(shader) ?? 'Unknown shader compile error';
    gl.deleteShader(shader);
    throw new Error(log);
  }
  return shader;
};

const createProgram = (gl: ExpoWebGLRenderingContext): WebGLProgram => {
  const vertex = compileShader(gl, gl.VERTEX_SHADER, VERT_SRC);
  const fragment = compileShader(gl, gl.FRAGMENT_SHADER, FRAG_SRC);
  const program = gl.createProgram();
  if (!program) throw new Error('Unable to create program.');
  gl.attachShader(program, vertex);
  gl.attachShader(program, fragment);
  gl.linkProgram(program);
  gl.deleteShader(vertex);
  gl.deleteShader(fragment);

  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    const log = gl.getProgramInfoLog(program) ?? 'Unknown program link error';
    gl.deleteProgram(program);
    throw new Error(log);
  }

  return program;
};

const hasAny = (set: Set<string>, names: string[]): number => {
  for (let i = 0; i < names.length; i += 1) {
    if (set.has(names[i])) return 1;
  }
  return 0;
};

export default function VisualizerCanvas({ frame, scene }: Props) {
  const glRef = useRef<ExpoWebGLRenderingContext | null>(null);
  const resourcesRef = useRef<GLResources | null>(null);
  const rafRef = useRef<number | null>(null);
  const frameRef = useRef<AnalyzerFrame>(frame);
  const sceneRef = useRef<SceneState>(scene);
  const startTimeRef = useRef<number>(Date.now());
  const [layoutSize, setLayoutSize] = useState({ width: 0, height: 0 });

  useEffect(() => {
    frameRef.current = frame;
  }, [frame]);

  useEffect(() => {
    sceneRef.current = scene;
  }, [scene]);

  const disposeResources = useCallback(() => {
    if (rafRef.current !== null) {
      cancelAnimationFrame(rafRef.current);
      rafRef.current = null;
    }
    const gl = glRef.current;
    const res = resourcesRef.current;
    if (gl && res) {
      gl.deleteTexture(res.audioTex);
      gl.deleteBuffer(res.quadBuffer);
      gl.deleteProgram(res.program);
    }
    resourcesRef.current = null;
    glRef.current = null;
  }, []);

  useEffect(
    () => () => {
      disposeResources();
    },
    [disposeResources]
  );

  const renderFrame = useCallback(() => {
    const gl = glRef.current;
    const res = resourcesRef.current;
    if (!gl || !res) return;

    const currentFrame = frameRef.current;
    const currentScene = sceneRef.current;
    const t = (Date.now() - startTimeRef.current) * 0.001;

    gl.viewport(0, 0, gl.drawingBufferWidth, gl.drawingBufferHeight);
    gl.clearColor(0.02, 0.02, 0.03, 1.0);
    gl.clear(gl.COLOR_BUFFER_BIT);

    gl.useProgram(res.program);

    const bins = currentFrame.bins;
    const binCount = bins.length;
    for (let i = 0; i < AUDIO_TEX_WIDTH; i += 1) {
      const src = Math.floor((i / AUDIO_TEX_WIDTH) * binCount);
      const v = Math.max(0, Math.min(1, bins[src] ?? 0));
      res.audioBytes[i] = Math.round(v * 255);
    }

    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, res.audioTex);
    gl.texSubImage2D(
      gl.TEXTURE_2D,
      0,
      0,
      0,
      AUDIO_TEX_WIDTH,
      1,
      gl.LUMINANCE,
      gl.UNSIGNED_BYTE,
      res.audioBytes
    );

    gl.bindBuffer(gl.ARRAY_BUFFER, res.quadBuffer);
    gl.enableVertexAttribArray(res.posLoc);
    gl.vertexAttribPointer(res.posLoc, 2, gl.FLOAT, false, 0, 0);

    const layers = currentScene.enabledLayers;
    const effects = currentScene.enabledEffects;

    const layerSpectrum = hasAny(layers as Set<string>, ['Spectrum', 'City', 'Fire']);
    const layerWaveform = hasAny(layers as Set<string>, ['Waveform', 'Liquid', 'DNA']);
    const layerRadial = hasAny(layers as Set<string>, [
      'Circular',
      'Crystal',
      'Magnetic',
      'Mandala Wave',
      'Yantra Star',
      'Lotus Petals',
      'Metatron Grid',
      'Black Hole',
      'Spiral Galaxy',
    ]);
    const layerObjects = hasAny(layers as Set<string>, [
      'Swarm',
      'Bouncing Orbs',
      'Comets',
      'Shards',
      'Orbiters',
      'Ribbons',
      'Neon Rain',
      'Bass Bubbles',
      'Neural',
      'Gravity',
    ]);

    const layerRings = hasAny(layers as Set<string>, ['Orbit', 'Pulse Rings']);
    const layerLaser = hasAny(layers as Set<string>, ['Laser Grid']);
    const layerSacred = hasAny(layers as Set<string>, ['Mandala Wave', 'Lotus Petals', 'Metatron Grid', 'Yantra Star']);
    const layerVu = hasAny(layers as Set<string>, ['VU']);

    const fxZoom = hasAny(effects as Set<string>, ['Camera Zoom']);
    const fxShake = hasAny(effects as Set<string>, ['Screen Shake']);
    const fxRotate = hasAny(effects as Set<string>, ['Camera Rotate']);
    const fxDrift = hasAny(effects as Set<string>, ['Screen Drift']);
    const fxInvert = hasAny(effects as Set<string>, ['Invert Colors']);
    const fxMirrorX = hasAny(effects as Set<string>, ['Mirror X']);
    const fxMirrorY = hasAny(effects as Set<string>, ['Mirror Y']);
    const fxTile = hasAny(effects as Set<string>, ['Tile']);
    const fxPixelate = hasAny(effects as Set<string>, ['Pixelate']);
    const fxScan = hasAny(effects as Set<string>, ['Scanlines']);
    const fxVignette = hasAny(effects as Set<string>, ['Vignette']);
    const fxStrobe = hasAny(effects as Set<string>, ['Strobe Flash']);
    const fxRgb = hasAny(effects as Set<string>, ['RGB Split']);
    const fxKaleido = hasAny(effects as Set<string>, ['Kaleidoscope']);
    const fxBloom = hasAny(effects as Set<string>, ['Bloom Glow']);
    const fxPrism = hasAny(effects as Set<string>, ['Prism Shift']);
    const fxSunburst = hasAny(effects as Set<string>, ['Solar Burst']);
    const fxSacredGrid = hasAny(effects as Set<string>, ['Sacred Grid']);

    gl.uniform2f(res.uResolution, gl.drawingBufferWidth, gl.drawingBufferHeight);
    gl.uniform1f(res.uTime, t);
    gl.uniform1i(res.uAudioTex, 0);
    gl.uniform4f(
      res.uLevels,
      currentFrame.bass,
      currentFrame.mids,
      currentFrame.highs,
      currentFrame.energy
    );
    gl.uniform1f(res.uFxIntensity, currentScene.fxIntensity);
    gl.uniform1f(res.uTileCount, currentScene.tileCount);
    gl.uniform1f(res.uSymmetry, currentScene.symmetrySegments);

    gl.uniform4f(res.uLayer, layerSpectrum, layerWaveform, layerRadial, layerObjects);
    gl.uniform4f(res.uLayer2, layerRings, layerLaser, layerSacred, layerVu);

    gl.uniform4f(res.uFxA, fxZoom, fxShake, fxRotate, fxDrift);
    gl.uniform4f(res.uFxB, fxInvert, fxMirrorX, fxMirrorY, fxTile);
    gl.uniform4f(res.uFxC, fxPixelate, fxScan, fxVignette, fxStrobe);
    gl.uniform4f(res.uFxD, fxRgb, fxKaleido, fxBloom, fxPrism);
    gl.uniform2f(res.uFxE, fxSunburst, fxSacredGrid);

    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    gl.disableVertexAttribArray(res.posLoc);
    gl.endFrameEXP();

    rafRef.current = requestAnimationFrame(renderFrame);
  }, []);

  const onContextCreate = useCallback(
    (gl: ExpoWebGLRenderingContext) => {
      disposeResources();
      glRef.current = gl;
      startTimeRef.current = Date.now();

      const program = createProgram(gl);
      const quadBuffer = gl.createBuffer();
      const audioTex = gl.createTexture();

      if (!quadBuffer || !audioTex) {
        throw new Error('Failed to create GL resources.');
      }

      const vertices = new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]);
      gl.bindBuffer(gl.ARRAY_BUFFER, quadBuffer);
      gl.bufferData(gl.ARRAY_BUFFER, vertices, gl.STATIC_DRAW);

      const audioBytes = new Uint8Array(AUDIO_TEX_WIDTH);
      gl.bindTexture(gl.TEXTURE_2D, audioTex);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.texImage2D(
        gl.TEXTURE_2D,
        0,
        gl.LUMINANCE,
        AUDIO_TEX_WIDTH,
        1,
        0,
        gl.LUMINANCE,
        gl.UNSIGNED_BYTE,
        audioBytes
      );

      resourcesRef.current = {
        program,
        quadBuffer,
        posLoc: gl.getAttribLocation(program, 'aPosition'),
        uResolution: gl.getUniformLocation(program, 'uResolution'),
        uTime: gl.getUniformLocation(program, 'uTime'),
        uAudioTex: gl.getUniformLocation(program, 'uAudioTex'),
        uLevels: gl.getUniformLocation(program, 'uLevels'),
        uFxIntensity: gl.getUniformLocation(program, 'uFxIntensity'),
        uTileCount: gl.getUniformLocation(program, 'uTileCount'),
        uSymmetry: gl.getUniformLocation(program, 'uSymmetry'),
        uLayer: gl.getUniformLocation(program, 'uLayer'),
        uLayer2: gl.getUniformLocation(program, 'uLayer2'),
        uFxA: gl.getUniformLocation(program, 'uFxA'),
        uFxB: gl.getUniformLocation(program, 'uFxB'),
        uFxC: gl.getUniformLocation(program, 'uFxC'),
        uFxD: gl.getUniformLocation(program, 'uFxD'),
        uFxE: gl.getUniformLocation(program, 'uFxE'),
        audioTex,
        audioBytes,
      };

      if (rafRef.current !== null) {
        cancelAnimationFrame(rafRef.current);
      }
      rafRef.current = requestAnimationFrame(renderFrame);
    },
    [disposeResources, renderFrame]
  );

  const onLayout = useCallback((event: LayoutChangeEvent) => {
    const nextWidth = Math.round(event.nativeEvent.layout.width);
    const nextHeight = Math.round(event.nativeEvent.layout.height);
    setLayoutSize((prev) => {
      if (prev.width === nextWidth && prev.height === nextHeight) {
        return prev;
      }
      return { width: nextWidth, height: nextHeight };
    });
  }, []);

  const glKey = useMemo(
    () => `gl-${scene.fullScreen ? 1 : 0}-${layoutSize.width}x${layoutSize.height}`,
    [layoutSize.height, layoutSize.width, scene.fullScreen]
  );

  return (
    <View style={styles.root} onLayout={onLayout}>
      {layoutSize.width > 0 && layoutSize.height > 0 ? (
        <GLView
          key={glKey}
          style={[styles.gl, { width: layoutSize.width, height: layoutSize.height }]}
          onContextCreate={onContextCreate}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#050505',
  },
  gl: {
    flex: 1,
  },
});
