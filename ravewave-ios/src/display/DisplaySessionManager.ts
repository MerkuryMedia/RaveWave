import { EmitterSubscription, Platform } from 'react-native';
import { AnalyzerFrame } from '../analyzer/AnalyzerEngine';
import { SceneState } from '../scene/sceneState';
import {
  createExternalDisplayEmitter,
  DISPLAY_EVENT_STATUS,
  externalDisplayBridge,
  ExternalDisplayStatusEvent,
  hasNativeExternalDisplayBridge,
} from './ExternalDisplayBridge';
import { DisplayFramePayload, DisplaySessionState } from './types';

type Options = {
  onState: (state: DisplaySessionState) => void;
};

const DEFAULT_STATE: DisplaySessionState = {
  supported: false,
  available: false,
  connected: false,
  active: false,
  controllerOnlyMode: false,
  transport: 'none',
  routeName: null,
  message: 'External display unavailable.',
};

const stateToMessage = (state: DisplaySessionState): string => {
  if (!state.supported) return state.message;
  if (state.active && state.connected) {
    return `External visuals live${state.routeName ? `: ${state.routeName}` : ''}`;
  }
  if (state.connected && !state.active) return 'External display detected. Start session to output visuals.';
  return state.message;
};

export class DisplaySessionManager {
  private onState: Options['onState'];

  private state: DisplaySessionState = DEFAULT_STATE;

  private emitterSub: EmitterSubscription | null = null;

  private lastPushMs = 0;

  constructor(options: Options) {
    this.onState = options.onState;
  }

  getState(): DisplaySessionState {
    return this.state;
  }

  async initialize(): Promise<void> {
    if (Platform.OS !== 'ios') {
      this.setState({
        ...DEFAULT_STATE,
        message: 'External display mode is currently iOS-only in this build.',
      });
      return;
    }

    if (!hasNativeExternalDisplayBridge()) {
      this.setState({
        ...DEFAULT_STATE,
        message: 'Use a custom iOS dev build with native external display bridge enabled.',
      });
      return;
    }

    const supported = await externalDisplayBridge.isSupported();
    this.setState({
      ...this.state,
      supported,
      available: supported,
      transport: supported ? 'external_display_native' : 'none',
      message: supported
        ? 'External display ready. Choose route and start session.'
        : 'External display bridge reported unsupported.',
    });

    const emitter = createExternalDisplayEmitter();
    if (emitter) {
      this.emitterSub = emitter.addListener(DISPLAY_EVENT_STATUS, (event: ExternalDisplayStatusEvent) => {
        this.setState({
          ...this.state,
          connected: event.connected ?? this.state.connected,
          active: event.active ?? this.state.active,
          controllerOnlyMode: (event.active ?? this.state.active) && (event.connected ?? this.state.connected),
          routeName:
            event.routeName === undefined ? this.state.routeName : event.routeName === null ? null : event.routeName,
          message: event.message ?? stateToMessage(this.state),
        });
      });
    }
  }

  async openRoutePicker(): Promise<boolean> {
    if (!this.state.supported) return false;
    return externalDisplayBridge.openRoutePicker();
  }

  async startSession(): Promise<boolean> {
    if (!this.state.supported) {
      return false;
    }

    const result = await externalDisplayBridge.startSession();
    if (typeof result === 'boolean') {
      const active = result;
      this.setState({
        ...this.state,
        connected: active || this.state.connected,
        active,
        controllerOnlyMode: active,
        message: active ? 'External visuals live.' : 'External display session not active.',
      });
      return active;
    }

    const active = !!result.connected;
    this.setState({
      ...this.state,
      connected: !!result.connected,
      active,
      controllerOnlyMode: active,
      routeName: result.routeName ?? this.state.routeName,
      message: result.message ?? (active ? 'External visuals live.' : 'External session failed to start.'),
    });
    return active;
  }

  async stopSession(): Promise<void> {
    await externalDisplayBridge.stopSession();
    this.setState({
      ...this.state,
      active: false,
      controllerOnlyMode: false,
      message: this.state.connected ? 'External display connected; session stopped.' : 'External session stopped.',
    });
  }

  pushFrame(scene: SceneState, frame: AnalyzerFrame): void {
    if (!this.state.active || !this.state.connected) return;

    const now = Date.now();
    if (now - this.lastPushMs < 40) {
      return;
    }
    this.lastPushMs = now;

    const payload: DisplayFramePayload = {
      t: now,
      audio: {
        bass: frame.bass,
        mids: frame.mids,
        highs: frame.highs,
        energy: frame.energy,
        beatPulse: frame.beatPulse,
        onsetPulse: frame.onsetPulse,
      },
      bins: frame.bins.slice(0, 64),
      scene: {
        enabledLayers: [...scene.enabledLayers],
        enabledEffects: [...scene.enabledEffects],
        fxIntensity: scene.fxIntensity,
        speed: scene.speed,
        tileCount: scene.tileCount,
        symmetrySegments: scene.symmetrySegments,
      },
    };

    externalDisplayBridge.pushFrame(payload);
  }

  dispose(): void {
    if (this.emitterSub) {
      this.emitterSub.remove();
      this.emitterSub = null;
    }
  }

  private setState(next: DisplaySessionState): void {
    this.state = next;
    this.onState(next);
  }
}
