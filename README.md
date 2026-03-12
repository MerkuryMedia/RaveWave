# RaveWave Android (Native)

RaveWave is a native Kotlin Android visualizer built for low-latency live control:
- audio source switching (mic / file playback / playback capture)
- centralized DSP/analyzer
- OpenGL ES layered rendering + post-processing
- phone-as-controller with external visual-only presentation
- cast session support with state-sync channel scaffold

## iPhone Build (No Mac Required)

A separate iPhone-compatible app is included at `ravewave-ios/` using Expo.

Quick start:
1. Install Expo Go on iPhone (App Store).
2. Run from project root:
   `powershell -ExecutionPolicy Bypass -File .\scripts\test-iphone.ps1`
3. Scan QR code on iPhone to launch.

Detailed iPhone docs:
- `ravewave-ios/README.md`

## Build

1. Open `C:\Servers\RaveWave` in Android Studio (Hedgehog+ with AGP 8.4+).
2. Let Gradle sync.
3. Run `app` on an Android 10+ device.
4. Grant microphone permission for mic mode.
5. For playback capture, use the consent dialog and ensure target app allows capture.

CLI build on this machine:
- `powershell -ExecutionPolicy Bypass -File .\scripts\build-debug.ps1`
- APK output: `app\build\outputs\apk\debug\app-debug.apk`

CLI install to connected USB device:
- `powershell -ExecutionPolicy Bypass -File .\scripts\install-debug.ps1`

Desktop test (Android Emulator):
- One-time (if accel check fails): `powershell -ExecutionPolicy Bypass -File .\scripts\install-hypervisor-driver.ps1`
- Start emulator: `powershell -ExecutionPolicy Bypass -File .\scripts\start-emulator.ps1`
- Install app to emulator: `powershell -ExecutionPolicy Bypass -File .\scripts\install-emulator.ps1`
- One-command flow (build + start emulator + install + launch): `powershell -ExecutionPolicy Bypass -File .\scripts\test-desktop.ps1`

No-BIOS desktop workflow (recommended if emulator virtualization is blocked):
- One-command flow (build + install to phone + launch + mirror on desktop): `powershell -ExecutionPolicy Bypass -File .\scripts\test-desktop-phone.ps1`

## Architecture

Primary packages:
- `audio/`: acquisition + source switching (`AudioSourceManager`, source adapters)
- `analyzer/`: DSP engine (`AudioAnalyzerEngine`, FFT, smoothing, bands, onset/beat)
- `render/`: OpenGL ES pipeline (`VisualizerRenderer`, scene shader, post shader, particle system)
- `scene/`: immutable scene state, toggles, presets, source mode, session flags
- `controls/`: controller UI + ViewModel + permission/source workflow
- `cast/`: cast session control + scene/audio state sync channel
- `display/`: external display presentation lifecycle and routing

## Source Modes

1. Microphone
- Uses `AudioRecord` mono PCM path.
- Runtime permission: `RECORD_AUDIO`.
- Fast stop/start source switching via `AudioSourceManager`.

2. File playback (in-app)
- Uses Media3 ExoPlayer for playback.
- File selected with `OpenDocument`.
- Audio-reactive feed from ExoPlayer audio session using `Visualizer` callback.

3. Playback capture (supported devices/apps)
- Uses `MediaProjection` + `AudioPlaybackCaptureConfiguration` + `AudioRecord`.
- Consent flow handled in controller activity.
- Fails gracefully when capture is not available/allowed.

All source types normalize to the analyzer input (`submitPcm`) and produce a shared reactive model.

## Renderer + Scene State

`SceneState` contains:
- enabled layers
- enabled post FX
- intensity, tile count, symmetry
- source mode
- fullscreen/cast/external flags
- active preset

UI updates `SceneRepository` only. Renderer consumes `SceneState` snapshots and analyzer metrics. UI widget state is never read directly by the renderer.

Rendering flow per frame:
1. analyzer snapshots waveform + bins
2. scene pass renders enabled layer set into FBO
3. pooled particle system overlays music-object visuals
4. post pass applies enabled FX stack from scene state to screen

## Dual-Screen / External Display

- Phone keeps controls.
- External display uses `Presentation` with its own `VisualizerSurfaceView`.
- External screen shows visuals only (no control UI).
- Scene changes from phone are pushed to both local and external renderer instances in real time.

## Cast Strategy

- Cast session control uses Google Cast framework sender APIs.
- `CastStateSyncChannel` sends compact scene/audio reactive state payloads through a Cast message namespace.
- Designed for a state-sync receiver architecture (not framebuffer streaming).

## Notes

- Pipeline is optimized around reusable buffers and GPU-side transforms.
- Analyzer runs off UI thread.
- Rendering runs on GLSurfaceView render thread.
- Presets are persisted via app shared preferences.
