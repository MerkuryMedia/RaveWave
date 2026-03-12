import { AnalyzerFrame } from '../analyzer/AnalyzerEngine';
import { ColorModes, PostEffects, SceneState, VisualLayers } from './sceneState';

const randomFloat = (min: number, max: number): number => min + Math.random() * (max - min);
const randomInt = (min: number, max: number): number => Math.floor(randomFloat(min, max + 1));

const shuffled = <T,>(items: readonly T[]): T[] => {
  const copy = [...items];
  for (let i = copy.length - 1; i > 0; i -= 1) {
    const j = Math.floor(Math.random() * (i + 1));
    [copy[i], copy[j]] = [copy[j], copy[i]];
  }
  return copy;
};

const pickSubset = <T,>(items: readonly T[], min: number, max: number): Set<T> => {
  const count = randomInt(Math.min(min, items.length), Math.min(max, items.length));
  return new Set(shuffled(items).slice(0, count));
};

const clamp = (value: number, min: number, max: number): number => Math.max(min, Math.min(max, value));

export type EvolvePulseState = {
  lastMutationAt: number;
  phraseMomentum: number;
};

export const createEvolvePulseState = (): EvolvePulseState => ({
  lastMutationAt: 0,
  phraseMomentum: 0,
});

export const randomizeScene = (scene: SceneState): SceneState => ({
  ...scene,
  enabledLayers: pickSubset(VisualLayers, 1, VisualLayers.length),
  enabledEffects: pickSubset(PostEffects, 0, Math.max(0, PostEffects.length - 2)),
  colorMode: ColorModes[randomInt(0, ColorModes.length - 1)],
  fxIntensity: Number(randomFloat(0.25, 1).toFixed(2)),
  speed: Number(randomFloat(0.2, 1).toFixed(2)),
  tileCount: randomInt(2, 6),
  symmetrySegments: randomInt(4, 12),
});

const toggleLayer = (layers: Set<(typeof VisualLayers)[number]>, addBias: number) => {
  const pool = shuffled(VisualLayers);
  const shouldAdd = layers.size <= 2 || (layers.size < 12 && Math.random() < addBias);
  if (shouldAdd) {
    const next = pool.find((layer) => !layers.has(layer));
    if (next) layers.add(next);
    return;
  }
  const removable = [...layers].filter((_, index, arr) => arr.length > 1);
  if (removable.length > 0) {
    layers.delete(removable[randomInt(0, removable.length - 1)]);
  }
};

const toggleEffect = (effects: Set<(typeof PostEffects)[number]>, addBias: number) => {
  const pool = shuffled(PostEffects);
  const shouldAdd = effects.size < 7 && Math.random() < addBias;
  if (shouldAdd) {
    const next = pool.find((effect) => !effects.has(effect));
    if (next) effects.add(next);
    return;
  }
  if (effects.size > 0) {
    const enabled = [...effects];
    effects.delete(enabled[randomInt(0, enabled.length - 1)]);
  }
};

export const evolveSceneWithAudio = (
  scene: SceneState,
  frame: AnalyzerFrame,
  pulseState: EvolvePulseState
): SceneState => {
  const now = frame.timestamp;
  const accent = frame.beatPulse * 0.55 + frame.onsetPulse * 0.35 + frame.attack * 0.1;
  pulseState.phraseMomentum = clamp(
    pulseState.phraseMomentum * 0.82 + frame.energy * 0.12 + frame.beatPulse * 0.22 + frame.onsetPulse * 0.18,
    0,
    1.8
  );

  const minInterval = 220 + (1 - accent) * 480;
  if (now - pulseState.lastMutationAt < minInterval) {
    return scene;
  }

  const mutationChance = accent * 0.9 + frame.energy * 0.18;
  if (Math.random() > mutationChance) {
    return scene;
  }

  pulseState.lastMutationAt = now;

  const nextLayers = new Set(scene.enabledLayers);
  const nextEffects = new Set(scene.enabledEffects);
  let nextColorMode = scene.colorMode;
  let nextFxIntensity = scene.fxIntensity;
  let nextSpeed = scene.speed;
  let nextTileCount = scene.tileCount;
  let nextSymmetry = scene.symmetrySegments;

  const phraseHit = pulseState.phraseMomentum > 1.15 && frame.beatPulse > 0.58;
  const mutationCount = phraseHit ? randomInt(2, 4) : accent > 0.72 ? randomInt(1, 3) : 1;

  for (let i = 0; i < mutationCount; i += 1) {
    const roll = Math.random();
    if (roll < 0.52) {
      toggleLayer(nextLayers, 0.74 - frame.decay * 0.2);
    } else if (roll < 0.8) {
      toggleEffect(nextEffects, 0.58 + frame.highs * 0.16);
    } else if (roll < 0.9) {
      if (phraseHit || Math.random() < frame.highs * 0.5) {
        nextColorMode = ColorModes[randomInt(0, ColorModes.length - 1)];
      }
    } else {
      nextFxIntensity = Number(clamp(nextFxIntensity + (frame.attack - frame.decay) * 0.08 + randomFloat(-0.04, 0.04), 0, 1).toFixed(2));
      nextSpeed = Number(clamp(nextSpeed + frame.beatPulse * 0.05 - frame.decay * 0.03 + randomFloat(-0.03, 0.03), 0, 1).toFixed(2));
      nextTileCount = clamp(nextTileCount + (phraseHit ? randomInt(-1, 1) : 0), 2, 6);
      nextSymmetry = clamp(nextSymmetry + (frame.highs > 0.62 && Math.random() < 0.4 ? randomInt(-1, 1) : 0), 4, 12);
    }
  }

  if (phraseHit) {
    pulseState.phraseMomentum *= 0.58;
  }

  return {
    ...scene,
    enabledLayers: nextLayers.size > 0 ? nextLayers : new Set([VisualLayers[randomInt(0, VisualLayers.length - 1)]]),
    enabledEffects: nextEffects,
    colorMode: nextColorMode,
    fxIntensity: nextFxIntensity,
    speed: nextSpeed,
    tileCount: nextTileCount,
    symmetrySegments: nextSymmetry,
  };
};

export const nextEvolveDelayMs = (frame: AnalyzerFrame): number => {
  const accent = frame.beatPulse * 0.6 + frame.onsetPulse * 0.25 + frame.energy * 0.15;
  return Math.round(180 + (1 - clamp(accent, 0, 1)) * 320);
};
