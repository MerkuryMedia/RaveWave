import * as DocumentPicker from 'expo-document-picker';
import { Audio, AVPlaybackStatus } from 'expo-av';
import { SourceMode } from '../scene/sceneState';

export type AudioCallbacks = {
  onLevel: (value01: number) => void;
  onSamples: (samples: number[]) => void;
  onStatus: (message: string) => void;
};

const clamp01 = (v: number) => Math.max(0, Math.min(1, v));

const dbToNorm = (db?: number): number => {
  if (db === undefined || db === null || Number.isNaN(db)) return 0;
  return clamp01((db + 60) / 60);
};

export class AudioSourceManager {
  private callbacks: AudioCallbacks;

  private mode: SourceMode = SourceMode.Microphone;

  private recording: Audio.Recording | null = null;

  private sound: Audio.Sound | null = null;

  private fileName: string | null = null;

  constructor(callbacks: AudioCallbacks) {
    this.callbacks = callbacks;
  }

  getFileName(): string | null {
    return this.fileName;
  }

  async setMode(mode: SourceMode): Promise<void> {
    this.mode = mode;
    if (mode === SourceMode.PlaybackCapture) {
      this.callbacks.onStatus('iOS does not allow system audio playback capture for third-party apps.');
      return;
    }

    if (mode === SourceMode.Microphone) {
      await this.startMicrophone();
    }
  }

  async pickFileAndPlay(): Promise<void> {
    await this.stopMicrophone();

    const result = await DocumentPicker.getDocumentAsync({
      type: ['audio/*'],
      multiple: false,
      copyToCacheDirectory: true,
    });

    if (result.canceled) {
      this.callbacks.onStatus('File selection canceled.');
      return;
    }

    const picked = result.assets[0];
    this.fileName = picked.name ?? 'Selected audio';

    if (this.sound) {
      await this.sound.unloadAsync();
      this.sound = null;
    }

    await Audio.setAudioModeAsync({
      allowsRecordingIOS: false,
      playsInSilentModeIOS: true,
      shouldDuckAndroid: true,
      interruptionModeIOS: 1,
      staysActiveInBackground: false,
    });

    const { sound } = await Audio.Sound.createAsync(
      { uri: picked.uri },
      {
        shouldPlay: true,
        progressUpdateIntervalMillis: 33,
      },
      (status) => this.handlePlaybackStatus(status),
      false
    );

    sound.setOnAudioSampleReceived((sample) => {
      const mono = sample.channels[0]?.frames ?? [];
      if (mono.length === 0) return;
      this.callbacks.onSamples(mono);

      let sum = 0;
      for (let i = 0; i < mono.length; i += 1) {
        sum += mono[i] * mono[i];
      }
      const rms = Math.sqrt(sum / mono.length);
      this.callbacks.onLevel(clamp01(rms * 2.2));
    });

    this.sound = sound;
    this.mode = SourceMode.File;
    this.callbacks.onStatus(`File playing: ${this.fileName}`);
  }

  async togglePlayPause(): Promise<void> {
    if (!this.sound) {
      this.callbacks.onStatus('No file loaded.');
      return;
    }

    const status = await this.sound.getStatusAsync();
    if (!status.isLoaded) {
      this.callbacks.onStatus('Sound not loaded.');
      return;
    }

    if (status.isPlaying) {
      await this.sound.pauseAsync();
      this.callbacks.onStatus('Playback paused.');
    } else {
      await this.sound.playAsync();
      this.callbacks.onStatus('Playback resumed.');
    }
  }

  async startMicrophone(): Promise<void> {
    await this.stopPlayback();

    const permission = await Audio.requestPermissionsAsync();
    if (!permission.granted) {
      this.callbacks.onStatus('Microphone permission denied.');
      return;
    }

    await Audio.setAudioModeAsync({
      allowsRecordingIOS: true,
      playsInSilentModeIOS: true,
      shouldDuckAndroid: true,
      interruptionModeIOS: 1,
      staysActiveInBackground: false,
    });

    const recording = new Audio.Recording();
    const options = {
      ...Audio.RecordingOptionsPresets.HIGH_QUALITY,
      isMeteringEnabled: true,
      keepAudioActiveHint: true,
    };

    await recording.prepareToRecordAsync(options as Audio.RecordingOptions);
    recording.setProgressUpdateInterval(33);
    recording.setOnRecordingStatusUpdate((status) => this.handleRecordingStatus(status));

    await recording.startAsync();
    this.recording = recording;
    this.mode = SourceMode.Microphone;
    this.callbacks.onStatus('Microphone live.');
  }

  async stopAll(): Promise<void> {
    await this.stopMicrophone();
    await this.stopPlayback();
  }

  private async stopMicrophone(): Promise<void> {
    if (!this.recording) return;
    try {
      await this.recording.stopAndUnloadAsync();
    } catch {
      // No-op if already stopped.
    }
    this.recording.setOnRecordingStatusUpdate(null);
    this.recording = null;
  }

  private async stopPlayback(): Promise<void> {
    if (!this.sound) return;
    this.sound.setOnAudioSampleReceived(null);
    await this.sound.unloadAsync();
    this.sound = null;
  }

  private handleRecordingStatus(status: Audio.RecordingStatus): void {
    if (!status.isRecording) return;
    const level = dbToNorm(status.metering);
    this.callbacks.onLevel(level);
  }

  private handlePlaybackStatus(status: AVPlaybackStatus): void {
    if (!status.isLoaded) {
      this.callbacks.onStatus('Playback error.');
      return;
    }
    if (status.didJustFinish) {
      this.callbacks.onStatus('Playback complete.');
    }
  }
}
