export type DisplayTransport = 'none' | 'external_display_native';

export type DisplaySessionState = {
  supported: boolean;
  available: boolean;
  connected: boolean;
  active: boolean;
  controllerOnlyMode: boolean;
  transport: DisplayTransport;
  routeName: string | null;
  message: string;
};

export type DisplayFramePayload = {
  t: number;
  audio: {
    bass: number;
    mids: number;
    highs: number;
    energy: number;
    beatPulse: number;
    onsetPulse: number;
  };
  bins: number[];
  scene: {
    enabledLayers: string[];
    enabledEffects: string[];
    fxIntensity: number;
    tileCount: number;
    symmetrySegments: number;
  };
};

