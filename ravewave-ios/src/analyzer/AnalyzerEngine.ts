export type AnalyzerFrame = {
  waveform: number[];
  bins: number[];
  bass: number;
  mids: number;
  highs: number;
  energy: number;
  beatPulse: number;
  onsetPulse: number;
  attack: number;
  decay: number;
  timestamp: number;
};

const clamp01 = (v: number) => Math.max(0, Math.min(1, v));

const avgSlice = (data: number[], start: number, end: number): number => {
  const s = Math.max(0, Math.floor(start));
  const e = Math.min(data.length, Math.floor(end));
  if (e <= s) return 0;
  let sum = 0;
  for (let i = s; i < e; i += 1) {
    sum += data[i];
  }
  return sum / (e - s);
};

export class AnalyzerEngine {
  private readonly waveformSize = 256;

  private readonly binCount = 96;

  private readonly waveform: number[] = new Array(this.waveformSize).fill(0);

  private readonly bins: number[] = new Array(this.binCount).fill(0);

  private smoothedBass = 0;

  private smoothedMids = 0;

  private smoothedHighs = 0;

  private smoothedEnergy = 0;

  private beatPulse = 0;

  private onsetPulse = 0;

  private prevEnergy = 0;

  private prevFlux = 0;

  submitLevel(level01: number): void {
    const value = clamp01(level01);
    for (let i = 0; i < this.waveform.length - 1; i += 1) {
      this.waveform[i] = this.waveform[i + 1];
    }
    const wobble = Math.sin(Date.now() * 0.018) * 0.25;
    this.waveform[this.waveform.length - 1] = clamp01(value + wobble) * 2 - 1;

    const time = Date.now() * 0.004;
    for (let i = 0; i < this.binCount; i += 1) {
      const p = i / (this.binCount - 1);
      const shaped = Math.abs(Math.sin(p * Math.PI * (3 + value * 12) + time));
      const target = shaped * value;
      this.bins[i] += (target - this.bins[i]) * 0.35;
    }
    this.updateMetricsFromBins();
  }

  submitSamples(samples: number[]): void {
    if (samples.length === 0) return;

    const copyLen = Math.min(samples.length, this.waveform.length);
    const offset = this.waveform.length - copyLen;
    const start = samples.length - copyLen;

    for (let i = 0; i < offset; i += 1) {
      this.waveform[i] = 0;
    }
    for (let i = 0; i < copyLen; i += 1) {
      const v = samples[start + i];
      this.waveform[offset + i] = Number.isFinite(v) ? Math.max(-1, Math.min(1, v)) : 0;
    }

    const stride = Math.max(1, Math.floor(this.waveform.length / this.binCount));
    for (let i = 0; i < this.binCount; i += 1) {
      let sum = 0;
      let count = 0;
      const binStart = i * stride;
      const binEnd = Math.min(this.waveform.length, binStart + stride);
      for (let j = binStart; j < binEnd; j += 1) {
        sum += Math.abs(this.waveform[j]);
        count += 1;
      }
      this.bins[i] = count > 0 ? sum / count : 0;
    }

    this.updateMetricsFromBins();
  }

  private updateMetricsFromBins(): void {
    const bass = avgSlice(this.bins, 0, this.binCount * 0.12);
    const mids = avgSlice(this.bins, this.binCount * 0.12, this.binCount * 0.45);
    const highs = avgSlice(this.bins, this.binCount * 0.45, this.binCount);
    const energy = (bass + mids + highs) / 3;

    this.smoothedBass += (bass - this.smoothedBass) * 0.22;
    this.smoothedMids += (mids - this.smoothedMids) * 0.18;
    this.smoothedHighs += (highs - this.smoothedHighs) * 0.15;
    this.smoothedEnergy += (energy - this.smoothedEnergy) * 0.16;

    let flux = 0;
    for (let i = 1; i < this.bins.length; i += 1) {
      const diff = this.bins[i] - this.bins[i - 1];
      if (diff > 0) flux += diff;
    }
    flux /= this.bins.length;

    const onset = Math.max(0, flux - this.prevFlux * 0.65);
    this.prevFlux = flux;

    this.onsetPulse = Math.max(this.onsetPulse * 0.82, onset * 2.2);

    const beatTrigger = Math.max(0, this.smoothedBass * 0.72 + onset * 0.52 - this.smoothedEnergy * 0.33);
    this.beatPulse = Math.max(this.beatPulse * 0.9, beatTrigger);

    this.prevEnergy = energy;
  }

  frame(): AnalyzerFrame {
    const delta = this.smoothedEnergy - this.prevEnergy;
    const attack = clamp01(Math.max(0, delta * 3));
    const decay = clamp01(Math.max(0, -delta * 2));

    return {
      waveform: this.waveform,
      bins: this.bins,
      bass: clamp01(this.smoothedBass),
      mids: clamp01(this.smoothedMids),
      highs: clamp01(this.smoothedHighs),
      energy: clamp01(this.smoothedEnergy),
      beatPulse: clamp01(this.beatPulse),
      onsetPulse: clamp01(this.onsetPulse),
      attack,
      decay,
      timestamp: Date.now(),
    };
  }
}
