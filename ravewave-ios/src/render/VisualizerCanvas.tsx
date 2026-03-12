import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { LayoutChangeEvent, StyleSheet, View } from 'react-native';
import { ExpoWebGLRenderingContext, GLView } from 'expo-gl';
import { AnalyzerFrame } from '../analyzer/AnalyzerEngine';
import { ColorModes, PostEffects, SceneState, VisualLayers } from '../scene/sceneState';
import { resolveRenderableLayers } from './renderBudget';

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
  uFftTex: WebGLUniformLocation | null;
  uWaveTex: WebGLUniformLocation | null;
  uLevels: WebGLUniformLocation | null;
  uPulse: WebGLUniformLocation | null;
  uFxIntensity: WebGLUniformLocation | null;
  uColorMode: WebGLUniformLocation | null;
  uSpeed: WebGLUniformLocation | null;
  uTileCount: WebGLUniformLocation | null;
  uSymmetry: WebGLUniformLocation | null;
  uLayerEnabled: WebGLUniformLocation | null;
  uEffectEnabled: WebGLUniformLocation | null;
  fftTex: WebGLTexture;
  waveTex: WebGLTexture;
  fftBytes: Uint8Array;
  waveBytes: Uint8Array;
  layerFlags: Float32Array;
  effectFlags: Float32Array;
};

const FFT_TEX_WIDTH = 96;
const WAVE_TEX_WIDTH = 256;

const VERT_SRC = `
attribute vec2 aPosition;
varying vec2 vUv;
void main() {
  vUv = (aPosition + 1.0) * 0.5;
  gl_Position = vec4(aPosition, 0.0, 1.0);
}
`;

const FRAG_SRC = `
#extension GL_OES_standard_derivatives : enable
precision mediump float;
varying vec2 vUv;

uniform vec2 uResolution;
uniform float uTime;
uniform sampler2D uFftTex;
uniform sampler2D uWaveTex;
uniform vec4 uLevels;      // bass, mids, highs, energy
uniform vec4 uPulse;       // beat, onset, attack, decay
uniform float uFxIntensity;
uniform float uColorMode;
uniform float uSpeed;
uniform float uTileCount;
uniform float uSymmetry;
uniform float uLayerEnabled[29];
uniform float uEffectEnabled[25];

const float PI = 3.141592653589793;
const float TAU = 6.283185307179586;

float fftAt(float x) {
  return texture2D(uFftTex, vec2(clamp(x, 0.0, 1.0), 0.5)).r;
}

float waveAt(float x) {
  return texture2D(uWaveTex, vec2(clamp(x, 0.0, 1.0), 0.5)).r * 2.0 - 1.0;
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
  vec2 uv = vUv;
  vec2 q = uv * 2.0 - 1.0;
  q.x *= uResolution.x / max(uResolution.y, 1.0);

  float bass = uLevels.x;
  float mids = uLevels.y;
  float highs = uLevels.z;
  float energy = uLevels.w;
  float beat = uPulse.x;
  float onset = uPulse.y;
  float attack = uPulse.z;
  float decay = uPulse.w;
  float nitrousKick = 0.0;
  float coverScale = 1.0;
  float fxSpeed = mix(0.45, 2.8, uSpeed);
  float bounceLift = 0.0;
  float tunnelPhase = 0.0;
  float tunnelScale = 1.0;

  vec2 drift = vec2(0.0);
  if (uEffectEnabled[1] > 0.5) {
    drift += vec2(sin(uTime * 31.0 * fxSpeed), cos(uTime * 27.0 * fxSpeed)) * (0.012 + bass * 0.03) * uFxIntensity;
  }
  if (uEffectEnabled[3] > 0.5) {
    drift += vec2(sin(uTime * 0.6 * fxSpeed), cos(uTime * 0.8 * fxSpeed)) * (0.05 + energy * 0.08) * uFxIntensity;
  }

  float zoom = 1.0;
  if (uEffectEnabled[0] > 0.5) {
    zoom += (bass * 0.22 + energy * 0.16 + beat * 0.1) * uFxIntensity;
  }
  if (uEffectEnabled[18] > 0.5) {
    nitrousKick = clamp(pow(bass * 0.95 + beat * 1.75 + onset * 0.85, 1.18), 0.0, 2.8);
    zoom += nitrousKick * (2.2 + uFxIntensity * 3.7);
    vec2 nitrousDir = length(q) > 0.0001 ? normalize(q) : vec2(0.0);
    q += nitrousDir * nitrousKick * 0.18 * (1.0 + uFxIntensity);
  }
  if (uEffectEnabled[23] > 0.5) {
    float bouncePulse = clamp(pow(bass * 0.72 + beat * 1.55 + onset * 0.65, 1.22), 0.0, 2.0);
    bounceLift = bouncePulse * (0.08 + uFxIntensity * 0.16) - decay * (0.03 + uFxIntensity * 0.04);
    zoom += bouncePulse * (0.04 + uFxIntensity * 0.06);
  }
  if (uEffectEnabled[24] > 0.5) {
    tunnelPhase = fract(uTime * (0.22 + beat * 0.05 + uFxIntensity * 0.08) * fxSpeed);
    tunnelScale = exp2(tunnelPhase * (1.5 + uFxIntensity * 1.9));
    zoom += 0.12 + uFxIntensity * 0.18;
  }
  q /= zoom;

  float angle = 0.0;
  if (uEffectEnabled[2] > 0.5) {
    angle += (sin(uTime * 1.1 * fxSpeed) * 0.14 + bass * 0.28 + beat * 0.18) * uFxIntensity;
  }
  if (uEffectEnabled[19] > 0.5) {
    angle += (uTime * 2.8 * fxSpeed + energy * 0.35) * (0.85 + uFxIntensity * 0.95);
  }
  if (uEffectEnabled[24] > 0.5) {
    angle += tunnelPhase * (0.16 + uFxIntensity * 0.28) + length(q) * 0.22;
  }
  q = rot(angle) * q;
  q.y += bounceLift;
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
    float px = mix(240.0, 80.0, clamp(uFxIntensity, 0.0, 1.0));
    sampleUv = floor(sampleUv * px) / px;
  }

  if (uEffectEnabled[24] > 0.5) {
    sampleUv = fract((sampleUv - 0.5) * tunnelScale + 0.5);
  }

  uv = clamp(sampleUv, 0.0, 1.0);
  vec2 p = uv * 2.0 - 1.0;
  p.x *= uResolution.x / max(uResolution.y, 1.0);
  float radius = length(p);
  float ang = atan(p.y, p.x);

  vec3 col = vec3(0.0);

  float stars = starField(uv + vec2(uTime * 0.005, 0.0), 48.0);
  stars += starField(uv * 1.9 + vec2(0.0, uTime * 0.004), 86.0);
  float nebula = fbm(p * 1.5 + vec2(uTime * 0.05, -uTime * 0.03));
  col += vec3(1.0) * stars * (0.45 + highs * 0.8);
  col += palette(nebula * 0.3 + radius * 0.08 + uTime * 0.02) * (0.08 + energy * 0.08) * exp(-radius * 2.2);
  col += vec3(0.04, 0.02, 0.08) * onset * exp(-radius * 3.0);

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
      col += palette(t + a * 0.25 + uTime * 0.03) * (body * 0.48 + cap * 0.75);
    }
  }

  if (uLayerEnabled[1] > 0.5) {
    float wave = waveAt(uv.x);
    float offset = 0.26 + bass * 0.14;
    float traceA = glow(p.y - wave * offset, 140.0);
    float traceB = glow(p.y + wave * offset * 0.66, 180.0);
    col += vec3(0.1, 0.96, 0.9) * traceA;
    col += vec3(0.72, 0.34, 1.0) * traceB * 0.7;
  }

  if (uLayerEnabled[2] > 0.5) {
    float band = fftAt(fract((ang + PI) / TAU));
    float target = 0.26 + band * 0.34 + bass * 0.08;
    float circular = glow(radius - target, 120.0);
    float spokes = pow(max(0.0, cos(ang * (8.0 + highs * 8.0))), 6.0) * exp(-radius * 1.3);
    col += palette(target + uTime * 0.04) * circular * 1.1;
    col += vec3(1.0, 0.7, 0.25) * spokes * (0.12 + highs * 0.3);
  }

  if (uLayerEnabled[3] > 0.5) {
    float meter = smoothstep(0.07, 0.0, abs(p.x)) * smoothstep(0.0, 0.06, p.y - (-1.0 + energy * 1.7));
    float peak = glow(p.y - (-1.0 + (energy + attack * 0.2) * 1.7), 180.0);
    col += vec3(0.18, 1.0, 0.46) * meter * 0.8;
    col += vec3(1.0, 0.95, 0.65) * peak * 0.65;
  }

  if (uLayerEnabled[4] > 0.5) {
    float core = exp(-radius * (7.0 - bass * 2.5));
    float ring = glow(radius - (0.18 + bass * 0.1), 90.0);
    float lens = sin((radius * 16.0 - uTime * (2.0 + bass * 6.0)) + ang * 4.0);
    col += vec3(1.0, 0.78, 0.32) * core * (0.35 + bass * 0.7);
    col += vec3(0.45, 0.82, 1.0) * ring * (0.2 + energy * 0.4);
    col += palette(radius * 0.4 + 0.2) * smoothstep(0.86, 1.0, lens) * exp(-radius * 1.6) * 0.3;
  }

  if (uLayerEnabled[5] > 0.5) {
    float spine = sin(p.y * 7.0 - uTime * 1.8);
    float strandA = glow(p.x - spine * (0.18 + mids * 0.18), 85.0);
    float strandB = glow(p.x + spine * (0.18 + mids * 0.18), 85.0);
    float rung = glow(sin(p.y * 24.0 + uTime * 3.0) * 0.14 - p.x, 35.0) * exp(-abs(p.x) * 4.0);
    col += vec3(0.2, 0.82, 1.0) * strandA * 0.8;
    col += vec3(1.0, 0.36, 0.78) * strandB * 0.7;
    col += vec3(1.0, 0.9, 0.62) * rung * 0.22;
  }

  if (uLayerEnabled[6] > 0.5) {
    float flow = fbm(p * 2.8 + vec2(0.0, -uTime * 0.5));
    float liquid = glow(sin(flow * 10.0 + p.x * 5.0 - uTime * 1.3), 4.0);
    col += palette(flow + uTime * 0.03) * liquid * (0.16 + mids * 0.34) * exp(-radius * 0.8);
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
      col += vec3(0.15, 0.92, 1.0) * block * 0.42;
      col += vec3(0.32, 0.6, 1.0) * reflect * 0.08;
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
    col += vec3(0.2, 0.82, 1.0) * network * 0.18;
    col += vec3(0.7, 0.9, 1.0) * filaments * highs * 0.28;
  }

  if (uLayerEnabled[9] > 0.5) {
    float orbitA = glow(radius - (0.34 + sin(uTime * 0.6) * 0.03), 120.0);
    float orbitB = glow(radius - (0.58 + cos(uTime * 0.4) * 0.04), 110.0);
    float satA = exp(-length(p - vec2(cos(uTime * 1.2), sin(uTime * 1.2)) * 0.34) * 48.0);
    float satB = exp(-length(p - vec2(cos(-uTime * 0.8 + 1.6), sin(-uTime * 0.8 + 1.6)) * 0.58) * 44.0);
    col += vec3(1.0, 0.92, 0.72) * orbitA * 0.4;
    col += vec3(0.38, 0.82, 1.0) * orbitB * 0.3;
    col += vec3(1.0, 0.6, 0.25) * satA;
    col += vec3(0.8, 0.9, 1.0) * satB;
  }

  if (uLayerEnabled[10] > 0.5) {
    vec2 swirl = rot(radius * (4.0 + bass * 6.0)) * p;
    float disk = exp(-abs(swirl.y) * 12.0) * exp(-radius * 2.4);
    float voidMask = smoothstep(0.22 + bass * 0.06, 0.0, radius);
    float rim = glow(radius - (0.22 + bass * 0.08), 140.0);
    col += vec3(1.0, 0.52, 0.12) * disk * 0.42;
    col += vec3(0.3, 0.85, 1.0) * rim * 0.55;
    col *= 1.0 - voidMask * 0.65;
  }

  if (uLayerEnabled[11] > 0.5) {
    float facets = abs(fract((ang / TAU) * 12.0 + radius * 0.7) - 0.5);
    float crystal = glow(facets - (0.12 + fftAt(fract((ang + PI) / TAU)) * 0.08), 55.0);
    col += vec3(0.62, 0.86, 1.0) * crystal * exp(-radius * 0.8) * 0.6;
  }

  if (uLayerEnabled[12] > 0.5) {
    float flameNoise = fbm(vec2(p.x * 1.8, p.y * 2.4 - uTime * 1.2));
    float flame = smoothstep(0.72, 0.15, p.y + flameNoise * (0.28 + bass * 0.18) + 0.82);
    flame *= smoothstep(0.9, 0.08, abs(p.x));
    col += mix(vec3(1.0, 0.24, 0.04), vec3(1.0, 0.82, 0.28), flameNoise) * flame * (0.4 + bass * 0.5);
  }

  if (uLayerEnabled[13] > 0.5) {
    float field = sin(ang * 3.0 + radius * 22.0 - uTime * (1.0 + mids * 4.0));
    float magnetic = smoothstep(0.95, 1.0, field) * exp(-radius * 0.6);
    col += vec3(0.3, 1.0, 0.84) * magnetic * 0.34;
  }

  if (uLayerEnabled[14] > 0.5) {
    float petals = abs(sin(ang * 6.0 + uTime * 0.6));
    float mandala = glow(radius - (0.28 + petals * 0.18 + fftAt(fract((ang + PI) / TAU)) * 0.08), 120.0);
    col += palette(petals * 0.4 + uTime * 0.02) * mandala * 0.9;
  }

  if (uLayerEnabled[15] > 0.5) {
    float lotus = glow(radius - (0.18 + abs(sin(ang * 8.0)) * 0.14 + beat * 0.06), 140.0);
    float inner = glow(radius - (0.08 + abs(sin(ang * 4.0 + uTime * 0.3)) * 0.06), 180.0);
    col += vec3(1.0, 0.4, 0.88) * lotus * 0.8;
    col += vec3(1.0, 0.82, 0.45) * inner * 0.5;
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
    col += vec3(1.0, 0.9, 0.68) * metatron * 0.22;
  }

  if (uLayerEnabled[17] > 0.5) {
    float triA = glow(equiTriangle(p, vec2(0.0), 0.9), 95.0);
    float triB = glow(equiTriangle(rot(PI / 3.0) * p, vec2(0.0), 0.9), 95.0);
    float triC = glow(equiTriangle(p, vec2(0.0), 0.55), 120.0);
    col += vec3(1.0, 0.86, 0.5) * (triA + triB + triC) * 0.36;
  }

  if (uLayerEnabled[18] > 0.5) {
    float spiral = sin(ang * 4.0 + log(radius + 0.08) * 11.0 - uTime * (1.0 + highs * 2.0));
    float arm = smoothstep(0.88, 1.0, spiral) * exp(-radius * 0.8);
    col += vec3(0.86, 0.92, 1.0) * arm * 0.4;
    col += vec3(1.0, 0.78, 0.46) * exp(-radius * 8.0) * (0.2 + bass * 0.5);
  }

  if (uLayerEnabled[19] > 0.5) {
    vec2 g = abs(fract(vec2(p.x * 8.0 / max(radius, 0.2), p.y * 8.0 + uTime * 0.6)) - 0.5);
    float laser = max(glow(g.x, 55.0), glow(g.y, 55.0)) * exp(-radius * 0.5);
    col += vec3(0.2, 1.0, 0.85) * laser * 0.26;
  }

  if (uLayerEnabled[20] > 0.5) {
    float rings = glow(fract(radius * 7.0 - uTime * (0.8 + bass * 2.4) - beat * 1.5) - 0.5, 9.0);
    col += palette(radius * 0.22 + uTime * 0.02) * rings * exp(-radius * 0.7) * 0.45;
  }

  if (uLayerEnabled[21] > 0.5) {
    for (int i = 0; i < 10; i++) {
      float fi = float(i);
      float phase = uTime * (0.6 + fi * 0.03) + fi * 1.7;
      vec2 c = vec2(sin(phase), cos(phase * 1.2)) * (0.18 + fi * 0.05);
      col += palette(fi * 0.08 + uTime * 0.03) * exp(-length(p - c) * (18.0 - energy * 6.0)) * 0.11;
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
      col += palette(fi * 0.13 + 0.2) * orb * 0.24;
    }
  }

  if (uLayerEnabled[23] > 0.5) {
    for (int i = 0; i < 7; i++) {
      float fi = float(i);
      vec2 head = vec2(fract(0.18 * fi + uTime * (0.11 + fi * 0.01)) * 2.4 - 1.2, sin(fi * 3.2 + uTime * 0.7) * 0.6);
      float streak = exp(-length(p - head) * 18.0);
      streak += lineSeg(p, head + vec2(-0.18, 0.0), head + vec2(0.18, 0.0), 26.0) * 0.7;
      col += vec3(1.0, 0.82, 0.58) * streak * 0.18;
    }
  }

  if (uLayerEnabled[24] > 0.5) {
    float shards = smoothstep(0.9, 1.0, sin(ang * 14.0 + radius * 9.0 - uTime * 1.4));
    col += palette(ang / TAU + 0.3) * shards * exp(-radius * 0.6) * 0.32;
  }

  if (uLayerEnabled[25] > 0.5) {
    for (int i = 0; i < 8; i++) {
      float fi = float(i);
      float pr = 0.22 + fi * 0.06 + sin(uTime * 0.4 + fi) * 0.02;
      vec2 c = vec2(cos(uTime * (0.5 + fi * 0.02) + fi), sin(uTime * (0.7 + fi * 0.03) + fi)) * pr;
      float depth = 0.5 + 0.5 * sin(uTime + fi * 1.7);
      col += palette(fi * 0.07 + depth * 0.2) * exp(-length(p - c) * (24.0 - depth * 10.0)) * 0.12;
    }
  }

  if (uLayerEnabled[26] > 0.5) {
    float ribbonA = glow(p.y - sin(p.x * 5.0 + uTime * 1.3) * (0.18 + mids * 0.2), 48.0);
    float ribbonB = glow(p.y - cos(p.x * 4.2 - uTime * 1.0) * (0.28 + highs * 0.14), 56.0);
    col += vec3(0.18, 0.86, 1.0) * ribbonA * 0.45;
    col += vec3(1.0, 0.35, 0.8) * ribbonB * 0.36;
  }

  if (uLayerEnabled[27] > 0.5) {
    vec2 rainUv = vec2(uv.x * 18.0, uv.y * 2.4 + uTime * (2.0 + highs * 4.0));
    float column = hash21(vec2(floor(rainUv.x), 1.0));
    float rain = glow(fract(rainUv.y + column * 8.0) - 0.5, 18.0) * smoothstep(0.45, 0.5, abs(fract(rainUv.x) - 0.5));
    col += vec3(0.0, 0.92, 1.0) * rain * 0.28;
  }

  if (uLayerEnabled[28] > 0.5) {
    for (int i = 0; i < 8; i++) {
      float fi = float(i);
      vec2 c = vec2(sin(fi * 2.2 + uTime * 0.5) * 0.55, -0.9 + fract(uTime * (0.08 + fi * 0.01) + fi * 0.17) * 2.2);
      float bubble = glow(length(p - c) - (0.05 + bass * 0.05), 120.0);
      col += vec3(0.36, 0.82, 1.0) * bubble * (0.18 + bass * 0.3);
    }
  }

  if (uEffectEnabled[18] > 0.5) {
    float smear = smoothstep(0.0, 0.18, nitrousKick) * (0.42 + uFxIntensity * 0.9);
    float shock = max(0.0, sin(uTime * 44.0 + beat * 4.0)) * beat * 0.28;
    col *= 1.0 + smear * 0.55 + shock;
    col = mix(col, pow(col, vec3(0.78)), min(0.32, smear * 0.28 + shock));
  }

  if (uEffectEnabled[20] > 0.5) {
    float lum = dot(col, vec3(0.2126, 0.7152, 0.0722));
    float edge = smoothstep(0.02, 0.16, length(vec2(dFdx(lum), dFdy(lum))) * (3.2 + uFxIntensity * 4.5));
    float pulse = 0.5 + 0.5 * sin(uTime * 9.0 * fxSpeed + ang * 5.0 + radius * 18.0);
    vec3 neon = mix(vec3(0.0, 1.0, 0.92), vec3(1.0, 0.15, 0.78), pulse);
    col += neon * edge * (0.34 + uFxIntensity * 0.58);
    col = mix(col, max(col, neon * (0.14 + edge * 0.45)), 0.42);
  }

  if (uEffectEnabled[22] > 0.5) {
    float lum = dot(col, vec3(0.2126, 0.7152, 0.0722));
    float warp = pow(max(0.0, 1.0 - radius), 2.4);
    float lanes = pow(max(0.0, cos(ang * (46.0 + highs * 26.0) - uTime * 8.0 * fxSpeed)), 28.0);
    float streak = lanes * warp * (0.24 + lum * 0.95 + uFxIntensity * 0.4);
    vec3 hyper = mix(vec3(0.72, 0.92, 1.0), vec3(1.0, 0.9, 0.65), 0.5 + 0.5 * sin(ang * 6.0));
    col += hyper * streak;
    col += col * streak * 0.45;
  }

  if (uEffectEnabled[12] > 0.5) {
    float split = (0.004 + highs * 0.012 + radius * 0.008) * (0.6 + uFxIntensity);
    col.r += fftAt(clamp(uv.x + split, 0.0, 1.0)) * 0.18;
    col.b += fftAt(clamp(uv.x - split, 0.0, 1.0)) * 0.18;
  }

  if (uEffectEnabled[15] > 0.5) {
    vec3 prism = vec3(col.b, col.r, col.g);
    col = mix(col, prism, 0.18 + uFxIntensity * 0.28);
  }

  if (uEffectEnabled[14] > 0.5) {
    float bloom = exp(-radius * 2.5) * (0.18 + energy * 0.5);
    bloom += exp(-abs(sin(ang * 8.0 + uTime * 0.6)) * 6.0) * exp(-radius * 1.3) * 0.08;
    col += palette(radius * 0.1 + uTime * 0.03) * bloom * (0.4 + uFxIntensity * 0.5);
  }

  if (uEffectEnabled[16] > 0.5) {
    float rays = pow(max(0.0, cos(ang * (18.0 + bass * 12.0) - uTime * 0.5)), 14.0);
    col += mix(vec3(1.0, 0.74, 0.3), vec3(1.0, 0.2, 0.72), step(0.0, sin(ang * 7.0))) * rays * exp(-radius * 1.1) * 0.35;
  }

  if (uEffectEnabled[17] > 0.5) {
    float grid = circleLine(p, 0.28, 90.0) + circleLine(p, 0.52, 70.0);
    grid += glow(abs(fract((ang + PI) / TAU * max(6.0, uSymmetry)) - 0.5), 34.0) * exp(-radius * 0.5);
    col += vec3(1.0, 0.92, 0.72) * grid * 0.12;
  }

  if (uEffectEnabled[9] > 0.5) {
    float scan = 0.92 + 0.08 * sin(uv.y * uResolution.y * 0.6 + uTime * 2.0);
    col *= scan;
  }

  if (uEffectEnabled[10] > 0.5) {
    float vignette = smoothstep(1.18, 0.28, radius);
    col *= mix(1.0, vignette, 0.6 + bass * 0.2);
  }

  if (uEffectEnabled[11] > 0.5) {
    float strobe = max(0.0, sin(uTime * 18.0 * fxSpeed)) * highs * (0.12 + uFxIntensity * 0.22);
    col = mix(col, vec3(1.0), strobe);
  }

  if (uEffectEnabled[4] > 0.5) {
    col = vec3(1.0) - col;
  }

  if (uEffectEnabled[21] > 0.5) {
    float lum = dot(col, vec3(0.2126, 0.7152, 0.0722));
    float edge = smoothstep(0.018, 0.14, length(vec2(dFdx(lum), dFdy(lum))) * (3.4 + uFxIntensity * 5.0));
    vec3 xray = mix(vec3(0.1, 0.92, 1.0), vec3(1.0, 0.28, 0.8), 0.5 + 0.5 * sin(uTime * 3.0 * fxSpeed + ang * 3.0));
    col = xray * edge;
  }

  col = applyColorMode(col, uColorMode, radius, ang, uTime);
  col = pow(max(col, vec3(0.0)), vec3(0.92));
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

const fillTextureBytes = (
  target: Uint8Array,
  source: number[],
  normalize: (value: number) => number
) => {
  const sourceCount = source.length;
  for (let i = 0; i < target.length; i += 1) {
    const src = Math.floor((i / target.length) * sourceCount);
    const v = Math.max(0, Math.min(1, normalize(source[src] ?? 0)));
    target[i] = Math.round(v * 255);
  }
};

export default function VisualizerCanvas({ frame, scene }: Props) {
  const glRef = useRef<ExpoWebGLRenderingContext | null>(null);
  const resourcesRef = useRef<GLResources | null>(null);
  const rafRef = useRef<number | null>(null);
  const frameRef = useRef<AnalyzerFrame>(frame);
  const sceneRef = useRef<SceneState>(scene);
  const renderLayersRef = useRef<Set<(typeof VisualLayers)[number]>>(resolveRenderableLayers(scene, 'expo'));
  const startTimeRef = useRef<number>(Date.now());
  const [layoutSize, setLayoutSize] = useState({ width: 0, height: 0 });

  useEffect(() => {
    frameRef.current = frame;
  }, [frame]);

  useEffect(() => {
    sceneRef.current = scene;
    renderLayersRef.current = resolveRenderableLayers(scene, 'expo');
  }, [scene]);

  const disposeResources = useCallback(() => {
    if (rafRef.current !== null) {
      cancelAnimationFrame(rafRef.current);
      rafRef.current = null;
    }
    const gl = glRef.current;
    const res = resourcesRef.current;
    if (gl && res) {
      gl.deleteTexture(res.fftTex);
      gl.deleteTexture(res.waveTex);
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
    const renderableLayers = renderLayersRef.current;
    const t = (Date.now() - startTimeRef.current) * 0.001;

    gl.viewport(0, 0, gl.drawingBufferWidth, gl.drawingBufferHeight);
    gl.clearColor(0.0, 0.0, 0.0, 1.0);
    gl.clear(gl.COLOR_BUFFER_BIT);

    fillTextureBytes(res.fftBytes, currentFrame.bins, (value) => value);
    fillTextureBytes(res.waveBytes, currentFrame.waveform, (value) => value * 0.5 + 0.5);

    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, res.fftTex);
    gl.texSubImage2D(
      gl.TEXTURE_2D,
      0,
      0,
      0,
      FFT_TEX_WIDTH,
      1,
      gl.LUMINANCE,
      gl.UNSIGNED_BYTE,
      res.fftBytes
    );

    gl.activeTexture(gl.TEXTURE1);
    gl.bindTexture(gl.TEXTURE_2D, res.waveTex);
    gl.texSubImage2D(
      gl.TEXTURE_2D,
      0,
      0,
      0,
      WAVE_TEX_WIDTH,
      1,
      gl.LUMINANCE,
      gl.UNSIGNED_BYTE,
      res.waveBytes
    );

    for (let i = 0; i < VisualLayers.length; i += 1) {
      res.layerFlags[i] = renderableLayers.has(VisualLayers[i]) ? 1 : 0;
    }
    for (let i = 0; i < PostEffects.length; i += 1) {
      res.effectFlags[i] = currentScene.enabledEffects.has(PostEffects[i]) ? 1 : 0;
    }

    gl.useProgram(res.program);
    gl.bindBuffer(gl.ARRAY_BUFFER, res.quadBuffer);
    gl.enableVertexAttribArray(res.posLoc);
    gl.vertexAttribPointer(res.posLoc, 2, gl.FLOAT, false, 0, 0);

    gl.uniform2f(res.uResolution, gl.drawingBufferWidth, gl.drawingBufferHeight);
    gl.uniform1f(res.uTime, t);
    gl.uniform1i(res.uFftTex, 0);
    gl.uniform1i(res.uWaveTex, 1);
    gl.uniform4f(res.uLevels, currentFrame.bass, currentFrame.mids, currentFrame.highs, currentFrame.energy);
    gl.uniform4f(
      res.uPulse,
      currentFrame.beatPulse,
      currentFrame.onsetPulse,
      currentFrame.attack,
      currentFrame.decay
    );
    gl.uniform1f(res.uFxIntensity, currentScene.fxIntensity);
    gl.uniform1f(res.uColorMode, ColorModes.indexOf(currentScene.colorMode));
    gl.uniform1f(res.uSpeed, currentScene.speed);
    gl.uniform1f(res.uTileCount, currentScene.tileCount);
    gl.uniform1f(res.uSymmetry, currentScene.symmetrySegments);
    gl.uniform1fv(res.uLayerEnabled, res.layerFlags);
    gl.uniform1fv(res.uEffectEnabled, res.effectFlags);

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
      const fftTex = gl.createTexture();
      const waveTex = gl.createTexture();

      if (!quadBuffer || !fftTex || !waveTex) {
        throw new Error('Failed to create GL resources.');
      }

      const vertices = new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]);
      gl.bindBuffer(gl.ARRAY_BUFFER, quadBuffer);
      gl.bufferData(gl.ARRAY_BUFFER, vertices, gl.STATIC_DRAW);

      const fftBytes = new Uint8Array(FFT_TEX_WIDTH);
      const waveBytes = new Uint8Array(WAVE_TEX_WIDTH);

      gl.bindTexture(gl.TEXTURE_2D, fftTex);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.texImage2D(
        gl.TEXTURE_2D,
        0,
        gl.LUMINANCE,
        FFT_TEX_WIDTH,
        1,
        0,
        gl.LUMINANCE,
        gl.UNSIGNED_BYTE,
        fftBytes
      );

      gl.bindTexture(gl.TEXTURE_2D, waveTex);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.texImage2D(
        gl.TEXTURE_2D,
        0,
        gl.LUMINANCE,
        WAVE_TEX_WIDTH,
        1,
        0,
        gl.LUMINANCE,
        gl.UNSIGNED_BYTE,
        waveBytes
      );

      resourcesRef.current = {
        program,
        quadBuffer,
        posLoc: gl.getAttribLocation(program, 'aPosition'),
        uResolution: gl.getUniformLocation(program, 'uResolution'),
        uTime: gl.getUniformLocation(program, 'uTime'),
        uFftTex: gl.getUniformLocation(program, 'uFftTex'),
        uWaveTex: gl.getUniformLocation(program, 'uWaveTex'),
        uLevels: gl.getUniformLocation(program, 'uLevels'),
        uPulse: gl.getUniformLocation(program, 'uPulse'),
        uFxIntensity: gl.getUniformLocation(program, 'uFxIntensity'),
        uColorMode: gl.getUniformLocation(program, 'uColorMode'),
        uSpeed: gl.getUniformLocation(program, 'uSpeed'),
        uTileCount: gl.getUniformLocation(program, 'uTileCount'),
        uSymmetry: gl.getUniformLocation(program, 'uSymmetry'),
        uLayerEnabled: gl.getUniformLocation(program, 'uLayerEnabled[0]'),
        uEffectEnabled: gl.getUniformLocation(program, 'uEffectEnabled[0]'),
        fftTex,
        waveTex,
        fftBytes,
        waveBytes,
        layerFlags: new Float32Array(VisualLayers.length),
        effectFlags: new Float32Array(PostEffects.length),
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
    backgroundColor: '#000000',
  },
  gl: {
    flex: 1,
  },
});
