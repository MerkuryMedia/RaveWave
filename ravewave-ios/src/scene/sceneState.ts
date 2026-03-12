export enum SourceMode {
  Microphone = 'microphone',
  File = 'file',
  PlaybackCapture = 'playback_capture',
}

export const VisualLayers = [
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
] as const;

export type VisualLayer = (typeof VisualLayers)[number];

export const PostEffects = [
  'Camera Zoom',
  'Screen Shake',
  'Camera Rotate',
  'Screen Drift',
  'Invert Colors',
  'Mirror X',
  'Mirror Y',
  'Tile',
  'Pixelate',
  'Scanlines',
  'Vignette',
  'Strobe Flash',
  'RGB Split',
  'Kaleidoscope',
  'Bloom Glow',
  'Prism Shift',
  'Solar Burst',
  'Sacred Grid',
  'Nitrous',
  'Cyclone',
  'Neon',
  'X-Ray',
  'Hyperspace',
  'Bounce',
  'Tunnel',
] as const;

export type PostEffect = (typeof PostEffects)[number];

export const ColorModes = [
  'Blue',
  'Green',
  'Yellow',
  'Red',
  'Purple',
  'Two-Tone',
  'Rainbow',
  'White',
] as const;

export type ColorMode = (typeof ColorModes)[number];

export type SceneState = {
  sourceMode: SourceMode;
  enabledLayers: Set<VisualLayer>;
  enabledEffects: Set<PostEffect>;
  colorMode: ColorMode;
  fxIntensity: number;
  speed: number;
  tileCount: number;
  symmetrySegments: number;
  menuHidden: boolean;
  fullScreen: boolean;
  externalOutputActive: boolean;
  externalDisplayConnected: boolean;
  externalRouteName: string | null;
};

export const defaultSceneState = (): SceneState => ({
  sourceMode: SourceMode.Microphone,
  enabledLayers: new Set<VisualLayer>(['Spectrum', 'Waveform']),
  enabledEffects: new Set<PostEffect>(['Bloom Glow']),
  colorMode: 'Rainbow',
  fxIntensity: 0.6,
  speed: 0.6,
  tileCount: 3,
  symmetrySegments: 6,
  menuHidden: false,
  fullScreen: false,
  externalOutputActive: false,
  externalDisplayConnected: false,
  externalRouteName: null,
});

export type PersistedPreset = {
  name: string;
  sourceMode: SourceMode;
  enabledLayers: VisualLayer[];
  enabledEffects: PostEffect[];
  colorMode: ColorMode;
  fxIntensity: number;
  speed: number;
  tileCount: number;
  symmetrySegments: number;
};

export const toPersistedPreset = (name: string, scene: SceneState): PersistedPreset => ({
  name,
  sourceMode: scene.sourceMode,
  enabledLayers: [...scene.enabledLayers],
  enabledEffects: [...scene.enabledEffects],
  colorMode: scene.colorMode,
  fxIntensity: scene.fxIntensity,
  speed: scene.speed,
  tileCount: scene.tileCount,
  symmetrySegments: scene.symmetrySegments,
});

export const fromPersistedPreset = (preset: PersistedPreset): SceneState => ({
  ...defaultSceneState(),
  sourceMode: preset.sourceMode,
  enabledLayers: new Set(preset.enabledLayers),
  enabledEffects: new Set(preset.enabledEffects),
  colorMode: preset.colorMode ?? 'Rainbow',
  fxIntensity: preset.fxIntensity,
  speed: preset.speed ?? 0.6,
  tileCount: preset.tileCount,
  symmetrySegments: preset.symmetrySegments,
});
