export type CastSessionState = {
  supported: boolean;
  active: boolean;
  routeName: string | null;
  message: string;
};

const DEFAULT_STATE: CastSessionState = {
  supported: false,
  active: false,
  routeName: null,
  message: 'Chromecast sender is not enabled in this build.',
};

export class CastSessionManager {
  getState(): CastSessionState {
    return DEFAULT_STATE;
  }

  async start(): Promise<boolean> {
    return false;
  }

  async stop(): Promise<void> {
    // No-op placeholder.
  }
}

