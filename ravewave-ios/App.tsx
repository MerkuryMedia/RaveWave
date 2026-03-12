import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Pressable,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import Slider from '@react-native-community/slider';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { StatusBar } from 'expo-status-bar';
import { AnalyzerEngine, AnalyzerFrame } from './src/analyzer/AnalyzerEngine';
import { AudioSourceManager } from './src/audio/AudioSourceManager';
import { DisplaySessionManager } from './src/display/DisplaySessionManager';
import { DisplaySessionState } from './src/display/types';
import VisualizerCanvas from './src/render/VisualizerCanvas';
import {
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

  const analyzerRef = useRef<AnalyzerEngine>(new AnalyzerEngine());
  const sceneRef = useRef<SceneState>(scene);

  const sourceManagerRef = useRef<AudioSourceManager | null>(null);
  const displayManagerRef = useRef<DisplaySessionManager | null>(null);

  useEffect(() => {
    sceneRef.current = scene;
  }, [scene]);

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
      clearInterval(displayTimer);
      displayManagerRef.current?.stopSession().catch(() => undefined);
      displayManagerRef.current?.dispose();
      sourceManagerRef.current?.stopAll().catch(() => undefined);
    };
  }, []);

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
    setScene((prev) => ({
      ...prev,
      externalOutputActive: false,
      fullScreen: false,
      menuHidden: false,
    }));
    setStatusText('External output stopped.');
  };

  const controlsHidden = !scene.externalOutputActive && (scene.menuHidden || scene.fullScreen);
  const floatingButtonLabel = scene.fullScreen ? 'Exit Fullscreen' : 'Show Menu';
  const onFloatingButtonPress = () => {
    if (scene.fullScreen) {
      setScene((prev) => ({ ...prev, fullScreen: false, menuHidden: false }));
      return;
    }
    setScene((prev) => ({ ...prev, menuHidden: false }));
  };

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
        <View style={scene.fullScreen ? styles.visualFull : styles.visualWindowed}>
          <VisualizerSurface analyzerRef={analyzerRef} scene={scene} />
        </View>
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
              <Pressable
                style={styles.btnSecondary}
                onPress={() => {
                  if (scene.externalOutputActive) {
                    Alert.alert('External output active', 'Fullscreen is disabled while controller mode is active.');
                    return;
                  }
                  setScene((prev) => ({ ...prev, fullScreen: !prev.fullScreen }));
                }}
              >
                <Text style={styles.btnText}>{scene.fullScreen ? 'Exit Fullscreen' : 'Fullscreen'}</Text>
              </Pressable>
              <Pressable
                style={styles.btnSecondary}
                onPress={() => setScene((prev) => ({ ...prev, menuHidden: !prev.menuHidden }))}
              >
                <Text style={styles.btnText}>{scene.menuHidden ? 'Show Menu' : 'Hide Menu'}</Text>
              </Pressable>
            </View>

            <Text style={styles.meta}>File: {fileName}</Text>
            <Text style={styles.meta}>Mode: {scene.sourceMode}</Text>
          </Section>

          <Section title="External Display">
            <View style={styles.buttonRow}>
              <Pressable style={styles.btn} onPress={chooseOutputRoute}>
                <Text style={styles.btnText}>Choose Route</Text>
              </Pressable>
              <Pressable style={styles.btn} onPress={startExternalOutput}>
                <Text style={styles.btnText}>Start External</Text>
              </Pressable>
              <Pressable style={styles.btnSecondary} onPress={stopExternalOutput}>
                <Text style={styles.btnText}>Stop External</Text>
              </Pressable>
            </View>
            <Text style={styles.meta}>Connected: {displayState.connected ? 'Yes' : 'No'}</Text>
            <Text style={styles.meta}>Controller Mode: {displayState.controllerOnlyMode ? 'Active' : 'Off'}</Text>
            <Text style={styles.meta}>Route: {displayState.routeName ?? 'None'}</Text>
            <Text style={styles.meta}>Status: {displayState.message}</Text>
          </Section>

          <Section title="Preset">
            <TextInput
              value={presetName}
              onChangeText={setPresetName}
              placeholder="Preset name"
              placeholderTextColor="#8b8b8b"
              style={styles.input}
            />
            <View style={styles.buttonRow}>
              <Pressable style={styles.btn} onPress={savePreset}>
                <Text style={styles.btnText}>Save Preset</Text>
              </Pressable>
              <Pressable style={styles.btn} onPress={loadPreset}>
                <Text style={styles.btnText}>Load Preset</Text>
              </Pressable>
            </View>
            <Text style={styles.meta}>Saved: {presetNames.join(', ') || 'none'}</Text>
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
      ) : (
        <Pressable style={styles.showMenuButton} onPress={onFloatingButtonPress}>
          <Text style={styles.btnText}>{floatingButtonLabel}</Text>
        </Pressable>
      )}
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
  showMenuButton: {
    position: 'absolute',
    top: 56,
    right: 14,
    zIndex: 50,
    elevation: 6,
    backgroundColor: 'rgba(0,0,0,0.72)',
    borderColor: '#374253',
    borderWidth: 1,
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 8,
  },
});
