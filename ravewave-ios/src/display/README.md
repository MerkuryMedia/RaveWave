# External Display Phase 1

This folder defines the controller/display split for iOS external output.

## JS Responsibilities

- `DisplaySessionManager` owns session lifecycle and state.
- `ExternalDisplayBridge` defines the native bridge contract and event names.
- App UI can run in controller-only mode (`externalOutputActive`) while visuals are driven externally.
- Analyzer + scene are streamed as compact state payloads (not framebuffers).

## Native iOS Bridge Contract

Native module name:

- `RaveWaveExternalDisplay`

Expected methods:

- `isSupported() -> Promise<boolean>`
- `openRoutePicker() -> Promise<boolean>`
- `startSession() -> Promise<{ connected?: boolean; routeName?: string; message?: string } | boolean>`
- `stopSession() -> Promise<void>`
- `pushFrame(payloadJson: string) -> void`

Expected event:

- `RaveWaveExternalDisplayStatus` with payload:
  - `connected?: boolean`
  - `active?: boolean`
  - `routeName?: string | null`
  - `message?: string`

## Payload Shape

`DisplayFramePayload` includes:

- timestamp
- compact audio-reactive values
- reduced FFT bins
- scene toggles + FX controls

This keeps latency low and bandwidth small for external render synchronization.

