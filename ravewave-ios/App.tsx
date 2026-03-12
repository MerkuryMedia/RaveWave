import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Modal,
  Pressable,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import { useCallback } from 'react';
import Slider from '@react-native-community/slider';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { StatusBar } from 'expo-status-bar';
import { AnalyzerEngine, AnalyzerFrame } from './src/analyzer/AnalyzerEngine';
import { AudioSourceManager } from './src/audio/AudioSourceManager';
import { DisplaySessionManager } from './src/display/DisplaySessionManager';
import { DisplaySessionState } from './src/display/types';
import VisualizerCanvas from './src/render/VisualizerCanvas';
import {
  createEvolvePulseState,
  evolveSceneWithAudio,
  nextEvolveDelayMs,
  randomizeScene,
} from './src/scene/sceneRandomizer';
import {
  ColorMode,
  ColorModes,
  defaultSceneState,
  fromPersistedPreset,
  PersistedPreset,
  PostEffect,
  PostEffects,
  SceneState,
  SourceMode,
  toPersistedPreset,
  VisualLayer,
  VisualLayers,
} from './src/scene/sceneState';

const PRESET_KEY = 'ravewave_ios_presets_v1';
const DEFAULT_DISPLAY_STATE: DisplaySessionState = {
  supported: false,
  available: false,
  connected: false,
  active: false,
  controllerOnlyMode: false,
  transport: 'none',
  routeName: null,
  message: 'External display unavailable.',
};

const BASE_LAYERS: VisualLayer[] = VisualLayers.slice(0, 14) as VisualLayer[];
const SACRED_LAYERS: VisualLayer[] = VisualLayers.slice(14, 18) as VisualLayer[];
const EXTRA_LAYERS: VisualLayer[] = VisualLayers.slice(18, 21) as VisualLayer[];
const MUSIC_LAYERS: VisualLayer[] = VisualLayers.slice(21) as VisualLayer[];

const ToggleRow = ({
  label,
  value,
  onChange,
}: {
  label: string;
  value: boolean;
  onChange: (v: boolean) => void;
}) => (
  <View style={styles.toggleRow}>
    <Text style={styles.toggleText}>{label}</Text>
    <Switch value={value} onValueChange={onChange} trackColor={{ false: '#3a3a3a', true: '#1ec9ff' }} />
  </View>
);

const Section = ({ title, children }: { title: string; children: React.ReactNode }) => (
  <View style={styles.section}>
    <Text style={styles.sectionTitle}>{title}</Text>
    {children}
  </View>
);

const CastScreenIcon = () => (
  <View style={styles.castIconFrame}>
    <View style={styles.castIconBox} />
    <View style={styles.castIconWaveOuter} />
    <View style={styles.castIconWaveInner} />
    <View style={styles.castIconDot} />
  </View>
);

const SegmentedOptionRow = ({
  options,
  value,
  onChange,
}: {
  options: readonly string[];
  value: string;
  onChange: (next: string) => void;
}) => (
  <View style={styles.optionWrap}>
    {options.map((option) => {
      const selected = option === value;
      return (
        <Pressable
          key={option}
          style={[styles.optionChip, selected && styles.optionChipSelected]}
          onPress={() => onChange(option)}
        >
          <Text style={[styles.optionChipText, selected && styles.optionChipTextSelected]}>{option}</Text>
        </Pressable>
      );
    })}
  </View>
);

const VisualizerSurface = React.memo(function VisualizerSurface({
  analyzerRef,
  scene,
}: {
  analyzerRef: React.MutableRefObject<AnalyzerEngine>;
  scene: SceneState;
}) {
  const [frame, setFrame] = useState<AnalyzerFrame>(() => analyzerRef.current.frame());

  useEffect(() => {
    const timer = setInterval(() => {
      setFrame(analyzerRef.current.frame());
    }, 16);

    return () => clearInterval(timer);
  }, [analyzerRef]);

  return <VisualizerCanvas frame={frame} scene={scene} />;
});

export default function App() {
  const [scene, setScene] = useState<SceneState>(defaultSceneState());
  const [statusText, setStatusText] = useState('Ready');
  const [fileName, setFileName] = useState('No file selected');
  const [presetName, setPresetName] = useState('');
  const [presetNames, setPresetNames] = useState<string[]>([]);
  const [displayState, setDisplayState] = useState<DisplaySessionState>(DEFAULT_DISPLAY_STATE);
  const [isEvolving, setIsEvolving] = useState(false);
  const [showVisualControls, setShowVisualControls] = useState(false);
  const [showCastOverlay, setShowCastOverlay] = useState(false);

  const analyzerRef = useRef<AnalyzerEngine>(new AnalyzerEngine());
  const sceneRef = useRef<SceneState>(scene);
  const evolveTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const evolvePulseRef = useRef(createEvolvePulseState());
  const visualControlsTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const sourceManagerRef = useRef<AudioSourceManager | null>(null);
  const displayManagerRef = useRef<DisplaySessionManager | null>(null);

  useEffect(() => {
    sceneRef.current = scene;
  }, [scene]);

  const clearEvolveTimer = useCallback(() => {
    if (evolveTimeoutRef.current !== null) {
      clearTimeout(evolveTimeoutRef.current);
      evolveTimeoutRef.current = null;
    }
  }, []);

  const revealVisualControls = useCallback(() => {
    if (visualControlsTimeoutRef.current !== null) {
      clearTimeout(visualControlsTimeoutRef.current);
    }
    setShowVisualControls(true);
    visualControlsTimeoutRef.current = setTimeout(() => {
      setShowVisualControls(false);
    }, 3200);
  }, []);

  useEffect(() => {
    sourceManagerRef.current = new AudioSourceManager({
      onLevel: (v) => analyzerRef.current.submitLevel(v),
      onSamples: (samples) => analyzerRef.current.submitSamples(samples),
      onStatus: (msg) => setStatusText(msg),
    });

    displayManagerRef.current = new DisplaySessionManager({
      onState: (next) => {
        setDisplayState(next);
        setScene((prev) => ({
          ...prev,
          externalDisplayConnected: next.connected,
          externalOutputActive: next.controllerOnlyMode,
          externalRouteName: next.routeName ?? null,
          fullScreen: next.controllerOnlyMode ? false : prev.fullScreen,
          menuHidden: next.controllerOnlyMode ? false : prev.menuHidden,
        }));
      },
    });
    displayManagerRef.current.initialize().catch((e) => {
      setStatusText(`Display init failed: ${String(e)}`);
    });

    sourceManagerRef.current.startMicrophone().catch((e) => {
      setStatusText(`Mic start failed: ${String(e)}`);
    });

    loadPresets().catch(() => undefined);

    const displayTimer = setInterval(() => {
      const manager = displayManagerRef.current;
      if (!manager) return;
      manager.pushFrame(sceneRef.current, analyzerRef.current.frame());
    }, 40);

    return () => {
      clearEvolveTimer();
      if (visualControlsTimeoutRef.current !== null) {
        clearTimeout(visualControlsTimeoutRef.current);
      }
      clearInterval(displayTimer);
      displayManagerRef.current?.stopSession().catch(() => undefined);
      displayManagerRef.current?.dispose();
      sourceManagerRef.current?.stopAll().catch(() => undefined);
    };
  }, [clearEvolveTimer]);

  const loadPresets = async () => {
    const raw = await AsyncStorage.getItem(PRESET_KEY);
    if (!raw) {
      setPresetNames([]);
      return;
    }
    const parsed = JSON.parse(raw) as PersistedPreset[];
    setPresetNames(parsed.map((p) => p.name));
  };

  const readAllPresets = async (): Promise<PersistedPreset[]> => {
    const raw = await AsyncStorage.getItem(PRESET_KEY);
    if (!raw) return [];
    return JSON.parse(raw) as PersistedPreset[];
  };

  const savePreset = async () => {
    const name = presetName.trim();
    if (!name) {
      Alert.alert('Preset name required', 'Enter a preset name before saving.');
      return;
    }

    const presets = await readAllPresets();
    const next = presets.filter((p) => p.name !== name);
    next.push(toPersistedPreset(name, scene));
    await AsyncStorage.setItem(PRESET_KEY, JSON.stringify(next));
    setPresetNames(next.map((p) => p.name));
    setStatusText(`Preset saved: ${name}`);
  };

  const loadPreset = async () => {
    const name = presetName.trim() || presetNames[0];
    if (!name) {
      Alert.alert('No preset', 'Save a preset first.');
      return;
    }

    const presets = await readAllPresets();
    const found = presets.find((p) => p.name === name);
    if (!found) {
      Alert.alert('Preset not found', `No preset named "${name}".`);
      return;
    }

    const nextScene = {
      ...fromPersistedPreset(found),
      menuHidden: scene.menuHidden,
      fullScreen: scene.fullScreen,
    };
    setScene(nextScene);
    setStatusText(`Preset loaded: ${name}`);
  };

  const setLayer = (layer: VisualLayer, enabled: boolean) => {
    setScene((prev) => {
      const next = new Set(prev.enabledLayers);
      if (enabled) next.add(layer);
      else next.delete(layer);
      return { ...prev, enabledLayers: next };
    });
  };

  const setEffect = (effect: PostEffect, enabled: boolean) => {
    setScene((prev) => {
      const next = new Set(prev.enabledEffects);
      if (enabled) next.add(effect);
      else next.delete(effect);
      return { ...prev, enabledEffects: next };
    });
  };

  const randomizeVisuals = useCallback(() => {
    setScene((prev) => randomizeScene(prev));
    setStatusText('Scene randomized.');
  }, []);

  useEffect(() => {
    clearEvolveTimer();
    if (!isEvolving) return undefined;

    setScene((prev) => randomizeScene(prev));
    setStatusText('Evolve enabled.');
    evolvePulseRef.current = createEvolvePulseState();

    const scheduleNext = () => {
      const frame = analyzerRef.current.frame();
      evolveTimeoutRef.current = setTimeout(() => {
        const liveFrame = analyzerRef.current.frame();
        setScene((prev) => evolveSceneWithAudio(prev, liveFrame, evolvePulseRef.current));
        scheduleNext();
      }, nextEvolveDelayMs(frame));
    };

    scheduleNext();
    return clearEvolveTimer;
  }, [clearEvolveTimer, isEvolving]);

  const selectMicrophone = async () => {
    await sourceManagerRef.current?.setMode(SourceMode.Microphone);
    setScene((prev) => ({ ...prev, sourceMode: SourceMode.Microphone }));
  };

  const selectFile = async () => {
    await sourceManagerRef.current?.pickFileAndPlay();
    const current = sourceManagerRef.current?.getFileName() ?? 'Selected audio';
    setFileName(current);
    setScene((prev) => ({ ...prev, sourceMode: SourceMode.File }));
  };

  const selectCapture = async () => {
    await sourceManagerRef.current?.setMode(SourceMode.PlaybackCapture);
    setScene((prev) => ({ ...prev, sourceMode: SourceMode.PlaybackCapture }));
  };

  const chooseOutputRoute = async () => {
    const opened = await displayManagerRef.current?.openRoutePicker();
    if (opened) return;
    Alert.alert(
      'External display unavailable',
      displayState.message ||
        'Use a custom iOS dev build with the native external display bridge and connect AirPlay/HDMI.'
    );
  };

  const startExternalOutput = async () => {
    const started = await displayManagerRef.current?.startSession();
    if (started) {
      setStatusText('External output started. Phone is now controller-only.');
      return;
    }
    const next = displayManagerRef.current?.getState();
    Alert.alert('Could not start external output', next?.message ?? 'No external route selected.');
  };

  const stopExternalOutput = async () => {
    await displayManagerRef.current?.stopSession();
    setShowCastOverlay(false);
    setScene((prev) => ({
      ...prev,
      externalOutputActive: false,
      fullScreen: false,
      menuHidden: false,
    }));
    setStatusText('External output stopped.');
  };

  const controlsHidden = !scene.externalOutputActive && scene.fullScreen;

  const statusLine = useMemo(
    () =>
      `${statusText} | Mode ${scene.sourceMode} | Output ${
        displayState.active ? `External${displayState.routeName ? ` (${displayState.routeName})` : ''}` : 'Local'
      }`,
    [displayState.active, displayState.routeName, scene.sourceMode, statusText]
  );

  return (
    <SafeAreaView style={styles.root}>
      <StatusBar style="light" hidden={scene.fullScreen} />

      {!scene.externalOutputActive ? (
        <Pressable
          style={scene.fullScreen ? styles.visualFull : styles.visualWindowed}
          onPress={revealVisualControls}
        >
          <VisualizerSurface analyzerRef={analyzerRef} scene={scene} />
          {showVisualControls ? (
            <>
              <Pressable
                style={styles.castOverlayButton}
                onPress={() => {
                  revealVisualControls();
                  setShowCastOverlay(true);
                }}
              >
                <CastScreenIcon />
              </Pressable>
              <Pressable
                style={styles.fullscreenOverlayButton}
                onPress={() => {
                  revealVisualControls();
                  setScene((prev) => ({ ...prev, fullScreen: !prev.fullScreen, menuHidden: false }));
                }}
              >
                <Text style={styles.overlayButtonText}>{scene.fullScreen ? 'Exit Fullscreen' : 'Fullscreen'}</Text>
              </Pressable>
            </>
          ) : null}
        </Pressable>
      ) : (
        <View style={styles.controllerModeBanner}>
          <Text style={styles.controllerModeTitle}>Controller Mode Active</Text>
          <Text style={styles.controllerModeMeta}>
            Visuals are routed to external display{scene.externalRouteName ? `: ${scene.externalRouteName}` : ''}.
          </Text>
        </View>
      )}

      {!controlsHidden ? (
        <ScrollView style={styles.controls} contentContainerStyle={styles.controlsContent}>
          <Text style={styles.header}>RaveWave iPhone</Text>
          <Text style={styles.status}>{statusLine}</Text>

          <Section title="Audio Source">
            <View style={styles.buttonRow}>
              <Pressable style={styles.btn} onPress={selectMicrophone}>
                <Text style={styles.btnText}>Microphone</Text>
              </Pressable>
              <Pressable style={styles.btn} onPress={selectFile}>
                <Text style={styles.btnText}>File</Text>
              </Pressable>
              <Pressable style={styles.btn} onPress={selectCapture}>
                <Text style={styles.btnText}>Playback Capture</Text>
              </Pressable>
            </View>

            <View style={styles.buttonRow}>
              <Pressable style={styles.btnSecondary} onPress={() => sourceManagerRef.current?.togglePlayPause()}>
                <Text style={styles.btnText}>Play/Pause</Text>
              </Pressable>
            </View>

            <Text style={styles.meta}>File: {fileName}</Text>
            <Text style={styles.meta}>Mode: {scene.sourceMode}</Text>
          </Section>

          <Section title="Automation">
            <View style={styles.buttonRow}>
              <Pressable style={styles.btn} onPress={randomizeVisuals}>
                <Text style={styles.btnText}>Randomize</Text>
              </Pressable>
              <Pressable
                style={isEvolving ? styles.btnSecondaryActive : styles.btnSecondary}
                onPress={() => {
                  setIsEvolving((prev) => {
                    const next = !prev;
                    if (!next) setStatusText('Evolve disabled.');
                    return next;
                  });
                }}
              >
                <Text style={styles.btnText}>{isEvolving ? 'Evolve: On' : 'Evolve: Off'}</Text>
              </Pressable>
            </View>
          </Section>

          <Section title="Post FX Controls">
            <Text style={styles.label}>FX Intensity {Math.round(scene.fxIntensity * 100)}</Text>
            <Slider
              value={scene.fxIntensity}
              minimumValue={0}
              maximumValue={1}
              onValueChange={(v) => setScene((prev) => ({ ...prev, fxIntensity: v }))}
              minimumTrackTintColor="#1ec9ff"
              maximumTrackTintColor="#3b3b3b"
            />

            <Text style={styles.label}>Speed {Math.round(scene.speed * 100)}</Text>
            <Slider
              value={scene.speed}
              minimumValue={0}
              maximumValue={1}
              onValueChange={(v) => setScene((prev) => ({ ...prev, speed: v }))}
              minimumTrackTintColor="#1ec9ff"
              maximumTrackTintColor="#3b3b3b"
            />

            <Text style={styles.label}>Tile Count {scene.tileCount}</Text>
            <Slider
              value={scene.tileCount}
              minimumValue={2}
              maximumValue={6}
              step={1}
              onValueChange={(v) => setScene((prev) => ({ ...prev, tileCount: v }))}
              minimumTrackTintColor="#1ec9ff"
              maximumTrackTintColor="#3b3b3b"
            />

            <Text style={styles.label}>Symmetry {scene.symmetrySegments}</Text>
            <Slider
              value={scene.symmetrySegments}
              minimumValue={4}
              maximumValue={12}
              step={1}
              onValueChange={(v) => setScene((prev) => ({ ...prev, symmetrySegments: v }))}
              minimumTrackTintColor="#1ec9ff"
              maximumTrackTintColor="#3b3b3b"
            />
          </Section>

          <Section title="Colorize">
            <SegmentedOptionRow
              options={ColorModes}
              value={scene.colorMode}
              onChange={(next) => setScene((prev) => ({ ...prev, colorMode: next as ColorMode }))}
            />
          </Section>

          <Section title="Base Layers">
            {BASE_LAYERS.map((layer) => (
              <ToggleRow
                key={layer}
                label={layer}
                value={scene.enabledLayers.has(layer)}
                onChange={(v) => setLayer(layer, v)}
              />
            ))}
          </Section>

          <Section title="Sacred Geometry">
            {SACRED_LAYERS.map((layer) => (
              <ToggleRow
                key={layer}
                label={layer}
                value={scene.enabledLayers.has(layer)}
                onChange={(v) => setLayer(layer, v)}
              />
            ))}
          </Section>

          <Section title="Extra Layers">
            {EXTRA_LAYERS.map((layer) => (
              <ToggleRow
                key={layer}
                label={layer}
                value={scene.enabledLayers.has(layer)}
                onChange={(v) => setLayer(layer, v)}
              />
            ))}
          </Section>

          <Section title="Music Objects">
            {MUSIC_LAYERS.map((layer) => (
              <ToggleRow
                key={layer}
                label={layer}
                value={scene.enabledLayers.has(layer)}
                onChange={(v) => setLayer(layer, v)}
              />
            ))}
          </Section>

          <Section title="Post FX">
            {PostEffects.map((effect) => (
              <ToggleRow
                key={effect}
                label={effect}
                value={scene.enabledEffects.has(effect)}
                onChange={(v) => setEffect(effect, v)}
              />
            ))}
          </Section>

          <View style={{ height: 28 }} />
        </ScrollView>
      ) : null}

      <Modal
        visible={showCastOverlay}
        transparent
        animationType="fade"
        onRequestClose={() => setShowCastOverlay(false)}
      >
        <Pressable style={styles.castModalBackdrop} onPress={() => setShowCastOverlay(false)}>
          <Pressable style={styles.castModalCard} onPress={() => undefined}>
            <Text style={styles.castModalTitle}>Send Visuals To Screen</Text>
            <Text style={styles.meta}>Connected: {displayState.connected ? 'Yes' : 'No'}</Text>
            <Text style={styles.meta}>Route: {displayState.routeName ?? 'None'}</Text>
            <Text style={styles.meta}>Status: {displayState.message}</Text>
            <View style={styles.buttonRow}>
              <Pressable style={styles.btn} onPress={chooseOutputRoute}>
                <Text style={styles.btnText}>Choose Route</Text>
              </Pressable>
              <Pressable style={styles.btn} onPress={startExternalOutput}>
                <Text style={styles.btnText}>Start Output</Text>
              </Pressable>
              <Pressable style={styles.btnSecondary} onPress={stopExternalOutput}>
                <Text style={styles.btnText}>Stop Output</Text>
              </Pressable>
            </View>
          </Pressable>
        </Pressable>
      </Modal>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#030305',
  },
  visualWindowed: {
    height: 280,
    borderBottomWidth: 1,
    borderBottomColor: '#121212',
  },
  visualFull: {
    flex: 1,
    borderBottomWidth: 0,
  },
  controllerModeBanner: {
    borderBottomWidth: 1,
    borderBottomColor: '#121212',
    backgroundColor: '#070a11',
    paddingHorizontal: 14,
    paddingVertical: 10,
  },
  controllerModeTitle: {
    color: '#c9ecff',
    fontSize: 14,
    fontWeight: '700',
    marginBottom: 4,
  },
  controllerModeMeta: {
    color: '#93a2b6',
    fontSize: 12,
  },
  controls: {
    flex: 1,
  },
  controlsContent: {
    padding: 12,
  },
  header: {
    color: '#f4f4f4',
    fontSize: 20,
    fontWeight: '700',
    marginBottom: 6,
  },
  status: {
    color: '#23c8ff',
    fontSize: 12,
    marginBottom: 12,
  },
  section: {
    backgroundColor: '#0d0f14',
    borderColor: '#1c2230',
    borderWidth: 1,
    borderRadius: 10,
    padding: 10,
    marginBottom: 10,
  },
  sectionTitle: {
    color: '#f0f0f0',
    fontSize: 14,
    fontWeight: '700',
    marginBottom: 8,
  },
  buttonRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    marginBottom: 8,
  },
  btn: {
    backgroundColor: '#1f72ff',
    borderRadius: 8,
    paddingVertical: 8,
    paddingHorizontal: 11,
  },
  btnSecondary: {
    backgroundColor: '#1a1d25',
    borderColor: '#273041',
    borderWidth: 1,
    borderRadius: 8,
    paddingVertical: 8,
    paddingHorizontal: 11,
  },
  btnSecondaryActive: {
    backgroundColor: '#103345',
    borderColor: '#1ec9ff',
    borderWidth: 1,
    borderRadius: 8,
    paddingVertical: 8,
    paddingHorizontal: 11,
  },
  btnText: {
    color: 'white',
    fontSize: 12,
    fontWeight: '600',
  },
  meta: {
    color: '#9fa6b5',
    fontSize: 12,
    marginBottom: 3,
  },
  input: {
    borderWidth: 1,
    borderColor: '#303848',
    backgroundColor: '#131923',
    color: 'white',
    borderRadius: 8,
    paddingHorizontal: 10,
    paddingVertical: 8,
    marginBottom: 8,
  },
  label: {
    color: '#d8e2f2',
    fontSize: 12,
    marginBottom: 2,
  },
  optionWrap: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  optionChip: {
    backgroundColor: '#0b0d12',
    borderColor: '#28313f',
    borderWidth: 1,
    borderRadius: 999,
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  optionChipSelected: {
    backgroundColor: '#103345',
    borderColor: '#1ec9ff',
  },
  optionChipText: {
    color: '#d4dbe7',
    fontSize: 13,
    fontWeight: '600',
  },
  optionChipTextSelected: {
    color: '#ebfbff',
  },
  toggleRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 4,
  },
  toggleText: {
    color: '#f2f6ff',
    fontSize: 13,
    flex: 1,
    marginRight: 10,
  },
  castOverlayButton: {
    position: 'absolute',
    top: 16,
    right: 14,
    zIndex: 50,
    elevation: 6,
    backgroundColor: 'rgba(0,0,0,0.72)',
    borderColor: '#374253',
    borderWidth: 1,
    width: 42,
    height: 42,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 8,
  },
  fullscreenOverlayButton: {
    position: 'absolute',
    right: 14,
    bottom: 16,
    zIndex: 50,
    elevation: 6,
    backgroundColor: 'rgba(0,0,0,0.72)',
    borderColor: '#374253',
    borderWidth: 1,
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 8,
  },
  overlayButtonText: {
    color: 'white',
    fontSize: 12,
    fontWeight: '600',
  },
  castIconFrame: {
    width: 18,
    height: 14,
  },
  castIconBox: {
    position: 'absolute',
    top: 0,
    right: 0,
    width: 18,
    height: 12,
    borderWidth: 1.7,
    borderColor: '#ffffff',
    borderRadius: 1,
  },
  castIconWaveOuter: {
    position: 'absolute',
    left: 0,
    bottom: 0,
    width: 10,
    height: 10,
    borderLeftWidth: 1.7,
    borderTopWidth: 1.7,
    borderColor: '#ffffff',
    borderTopLeftRadius: 10,
  },
  castIconWaveInner: {
    position: 'absolute',
    left: 3,
    bottom: 0,
    width: 6,
    height: 6,
    borderLeftWidth: 1.7,
    borderTopWidth: 1.7,
    borderColor: '#ffffff',
    borderTopLeftRadius: 6,
  },
  castIconDot: {
    position: 'absolute',
    left: 0,
    bottom: 0,
    width: 3,
    height: 3,
    borderRadius: 1.5,
    backgroundColor: '#ffffff',
  },
  castModalBackdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.62)',
    justifyContent: 'center',
    padding: 20,
  },
  castModalCard: {
    backgroundColor: '#0d0f14',
    borderRadius: 14,
    borderWidth: 1,
    borderColor: '#1c2230',
    padding: 16,
  },
  castModalTitle: {
    color: '#f4f6fb',
    fontSize: 16,
    fontWeight: '700',
    marginBottom: 10,
  },
});
