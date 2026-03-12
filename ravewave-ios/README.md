# RaveWave iPhone Build (Expo)

This folder contains an iPhone-compatible build path that works without a Mac by using Expo Go.

## What Works

- Native iPhone app runtime through Expo Go
- Audio source switching:
  - Microphone (live metering-driven visuals)
  - Local file playback (audio file picker + playback + sample-driven visuals)
  - Playback capture button included with clear iOS limitation message
- Full visual control panel:
  - Layer toggles
  - Post FX toggles
  - Intensity, tile count, symmetry sliders
  - Fullscreen and hide/show menu
  - Preset save/load

## External Display / Cast (Phase 1)

- Controller/display split architecture is implemented in `src/display/`.
- Phone can switch to controller-only mode state.
- Scene + audio-reactive values are prepared for low-bandwidth external sync.
- Native bridge contract is defined for a real iOS external display renderer (`RaveWaveExternalDisplay`).

### Current Runtime Note

- Expo Go does **not** include the custom native bridge module.
- External display buttons will show guidance in Expo Go.
- To activate real external display output, build a custom iOS dev build or release IPA with the native bridge.

## iOS Limitation

Apple does not allow third-party apps to capture arbitrary device/app playback audio in the same way as Android MediaProjection. The app shows this clearly when `Playback Capture` is selected.

## Run On iPhone (No Mac)

1. Install **Expo Go** on your iPhone from the App Store.
2. On your PC terminal:

```powershell
cd C:\Servers\RaveWave\ravewave-ios
npm install
npm run iphone
```

3. Scan the QR code from terminal/browser using iPhone Camera or Expo Go.
4. App opens on iPhone. Grant microphone permission when prompted.

If your network blocks LAN discovery, use tunnel mode explicitly:

```powershell
npm run start:tunnel
```

## Type Check

```powershell
npm run typecheck
```

## Optional: Build Real iOS IPA In Cloud

You can produce an installable iOS build without a Mac via Expo EAS (Apple Developer account required):

```powershell
npm install -g eas-cli
cd C:\Servers\RaveWave\ravewave-ios
eas login
eas build:configure
eas build -p ios --profile preview
```

Follow EAS prompts to register bundle ID/signing.
