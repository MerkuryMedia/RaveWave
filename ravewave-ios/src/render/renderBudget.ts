import { PostEffect, SceneState, VisualLayer } from '../scene/sceneState';

type RenderProfile = 'expo' | 'native';

const CORE_LAYERS = new Set<VisualLayer>(['Spectrum', 'Waveform', 'Circular', 'Pulse Rings']);

const LAYER_COST: Record<VisualLayer, number> = {
  Spectrum: 1.0,
  Waveform: 1.0,
  Circular: 1.1,
  VU: 1.0,
  Gravity: 1.15,
  DNA: 1.15,
  Liquid: 1.2,
  City: 1.2,
  Neural: 1.3,
  Orbit: 1.1,
  'Black Hole': 1.35,
  Crystal: 1.25,
  Fire: 1.2,
  Magnetic: 1.25,
  'Mandala Wave': 1.3,
  'Lotus Petals': 1.2,
  'Metatron Grid': 1.1,
  'Yantra Star': 1.1,
  'Spiral Galaxy': 1.35,
  'Laser Grid': 1.1,
  'Pulse Rings': 1.0,
  Swarm: 1.8,
  'Bouncing Orbs': 1.55,
  Comets: 1.7,
  Shards: 1.65,
  Orbiters: 1.7,
  Ribbons: 1.45,
  'Neon Rain': 1.45,
  'Bass Bubbles': 1.45,
};

const EFFECT_LOAD: Partial<Record<PostEffect, number>> = {
  'Camera Zoom': 0.35,
  'Screen Shake': 0.6,
  'Camera Rotate': 0.45,
  'Screen Drift': 0.55,
  Tile: 0.85,
  Pixelate: 0.2,
  'RGB Split': 0.75,
  Kaleidoscope: 1.0,
  'Bloom Glow': 0.7,
  'Prism Shift': 0.35,
  'Solar Burst': 0.55,
  'Sacred Grid': 0.45,
  Nitrous: 1.45,
  Cyclone: 1.0,
  Neon: 1.0,
  'X-Ray': 1.15,
  Hyperspace: 1.3,
  Bounce: 0.55,
  Tunnel: 1.1,
};

const getLayerCategory = (layer: VisualLayer): string => {
  const index = [
    'Spectrum',
    'Waveform',
    'Circular',
    'VU',
    'Gravity',
    'DNA',
    'Liquid',
    'City',
    'Neural',
    'Orbit',
    'Black Hole',
    'Crystal',
    'Fire',
    'Magnetic',
    'Mandala Wave',
    'Lotus Petals',
    'Metatron Grid',
    'Yantra Star',
    'Spiral Galaxy',
    'Laser Grid',
    'Pulse Rings',
    'Swarm',
    'Bouncing Orbs',
    'Comets',
    'Shards',
    'Orbiters',
    'Ribbons',
    'Neon Rain',
    'Bass Bubbles',
  ].indexOf(layer);

  if (index <= 13) return 'base';
  if (index <= 17) return 'sacred';
  if (index <= 20) return 'extra';
  return 'music';
};

const getBudgetForScene = (scene: SceneState, profile: RenderProfile): number => {
  const baseBudget = profile === 'expo' ? 15.5 : 18.0;
  const minBudget = profile === 'expo' ? 6.5 : 8.0;
  const maxBudget = profile === 'expo' ? 16.0 : 18.5;

  let budget = baseBudget;
  budget -= scene.fxIntensity * (profile === 'expo' ? 3.4 : 2.6);
  budget -= scene.speed * (profile === 'expo' ? 1.1 : 0.8);

  let activeEffects = 0;
  scene.enabledEffects.forEach((effect) => {
    activeEffects += 1;
    budget -= EFFECT_LOAD[effect] ?? 0.25;
  });

  if (activeEffects > 3) {
    budget -= (activeEffects - 3) * 0.25;
  }

  return Math.max(minBudget, Math.min(maxBudget, budget));
};

export const resolveRenderableLayers = (
  scene: SceneState,
  profile: RenderProfile = 'expo'
): Set<VisualLayer> => {
  const activeLayers = [...scene.enabledLayers];
  if (activeLayers.length <= 1) return new Set(activeLayers);

  const budget = getBudgetForScene(scene, profile);
  const selected = [...activeLayers];
  let totalCost = selected.reduce((sum, layer) => sum + LAYER_COST[layer], 0);

  if (totalCost <= budget) {
    return new Set(selected);
  }

  while (selected.length > 2 && totalCost > budget) {
    const categoryCounts = new Map<string, number>();
    selected.forEach((layer) => {
      const category = getLayerCategory(layer);
      categoryCounts.set(category, (categoryCounts.get(category) ?? 0) + 1);
    });

    let removeIndex = -1;
    let bestScore = Number.NEGATIVE_INFINITY;

    for (let i = 0; i < selected.length; i += 1) {
      const layer = selected[i];
      const age = selected.length > 1 ? 1 - i / (selected.length - 1) : 0;
      const category = getLayerCategory(layer);
      let score = LAYER_COST[layer] * 1.35 + age * 0.85;

      if (CORE_LAYERS.has(layer)) score -= 1.2;
      if ((categoryCounts.get(category) ?? 0) <= 1) score -= 0.55;

      if (score > bestScore) {
        bestScore = score;
        removeIndex = i;
      }
    }

    if (removeIndex < 0) break;
    totalCost -= LAYER_COST[selected[removeIndex]];
    selected.splice(removeIndex, 1);
  }

  return new Set(selected);
};
