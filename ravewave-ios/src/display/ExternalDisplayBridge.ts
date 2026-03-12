import { NativeEventEmitter, NativeModules, Platform } from 'react-native';
import { DisplayFramePayload } from './types';

const MODULE_NAME = 'RaveWaveExternalDisplay';
export const DISPLAY_EVENT_STATUS = 'RaveWaveExternalDisplayStatus';

type NativeStartResult =
  | boolean
  | {
      connected?: boolean;
      routeName?: string | null;
      message?: string;
    };

export type ExternalDisplayStatusEvent = {
  connected?: boolean;
  active?: boolean;
  routeName?: string | null;
  message?: string;
};

type NativeExternalDisplayModule = {
  isSupported?: () => Promise<boolean> | boolean;
  startSession?: () => Promise<NativeStartResult>;
  stopSession?: () => Promise<void>;
  openRoutePicker?: () => Promise<boolean>;
  pushFrame?: (payload: string) => void;
};

const moduleRef = (NativeModules as Record<string, NativeExternalDisplayModule | undefined>)[MODULE_NAME];

export const hasNativeExternalDisplayBridge = (): boolean => Platform.OS === 'ios' && !!moduleRef;

export const createExternalDisplayEmitter = (): NativeEventEmitter | null => {
  if (!hasNativeExternalDisplayBridge() || !moduleRef) return null;
  return new NativeEventEmitter(moduleRef as never);
};

export const externalDisplayBridge = {
  async isSupported(): Promise<boolean> {
    if (!hasNativeExternalDisplayBridge() || !moduleRef) return false;
    if (!moduleRef.isSupported) return true;
    const value = await moduleRef.isSupported();
    return !!value;
  },

  async startSession(): Promise<NativeStartResult> {
    if (!hasNativeExternalDisplayBridge() || !moduleRef?.startSession) {
      return { connected: false, message: 'Native external display bridge unavailable.' };
    }
    return moduleRef.startSession();
  },

  async stopSession(): Promise<void> {
    if (!hasNativeExternalDisplayBridge() || !moduleRef?.stopSession) return;
    await moduleRef.stopSession();
  },

  async openRoutePicker(): Promise<boolean> {
    if (!hasNativeExternalDisplayBridge() || !moduleRef?.openRoutePicker) return false;
    return moduleRef.openRoutePicker();
  },

  pushFrame(payload: DisplayFramePayload): void {
    if (!hasNativeExternalDisplayBridge() || !moduleRef?.pushFrame) return;
    moduleRef.pushFrame(JSON.stringify(payload));
  },
};

