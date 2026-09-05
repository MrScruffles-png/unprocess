# Unprocess for Pixel

A lightweight Android camera focused on capturing photographs with as little computational processing as possible.

Built and tested primarily for the **Google Pixel 9 Pro**.

Unprocess uses Android Camera2 and a `RAW_SENSOR` still-capture path. It does not request the Pixel computational JPEG stream for still photography.

## Features

- Direct **DNG** capture from `RAW_SENSOR`
- **JPEG (RAW)** rendered locally from the captured RAW/DNG
- **DNG + JPEG** from the same RAW exposure
- Pixel 9 Pro physical **0.5× / 1× / 5×** rear cameras where RAW is exposed by Camera2
- RAW-capable front camera support
- Pinch-to-zoom with matching RAW-derived JPEG framing
- Automatic and manual exposure
- Discrete, haptic ISO / shutter / EV / focus controls
- Standard, Highlight, and Spot metering
- AE lock
- Manual focus where supported
- Metered still-capture flash
- Grid overlay and tap-to-focus / tap-to-meter
- Portrait-locked shooting UI with correct capture orientation metadata
- Material 3 / Android 16-inspired interface with dynamic color
- Lightweight release build with code and resource shrinking

## Capture formats

### DNG

The sensor RAW frame is written directly with Android's `DngCreator`.

### JPEG (RAW)

The app captures the same `RAW_SENSOR` frame, writes a temporary DNG, decodes that DNG with Android's basic decoder, and compresses the result as JPEG.

A JPEG cannot remain literally RAW: demosaicing, color conversion, and JPEG compression are required to produce a viewable image. The important distinction is that Unprocess does **not** request a Camera2 `ImageFormat.JPEG` still stream, so the JPEG is not taken from the normal Pixel computational JPEG pipeline.

### Both

Saves a DNG and a RAW-derived JPEG from the same exposure.

## Exposure controls

**Auto** provides three metering modes:

- **Standard** — normal Camera2 automatic exposure
- **Highlight** — automatic exposure with a negative bias to protect bright areas
- **Spot** — meters a small high-weight area in the center; tapping the preview moves the metering point

**Manual** provides ISO, shutter speed, autofocus/manual focus, and focus distance where Camera2 reports support.

The sliders use discrete photographic stops and haptic detents rather than arbitrary continuous values.

## Compatibility

Designed and tested primarily for the **Google Pixel 9 Pro**.

Other Android phones may work if they expose the required Camera2 RAW capabilities, but they are not currently guaranteed or officially tested.

## Storage

Captured media is saved through MediaStore under:

```text
DCIM/Unprocess
```

## Build

1. Open the project in a recent Android Studio version.
2. Run **File → Sync Project with Gradle Files**.
3. Install the Android SDK requested by the project if necessary.
4. Build a debug APK with:

```bash
./gradlew :app:assembleDebug
```

The APK will be written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

For a public release, generate a **signed release APK** in Android Studio and keep the signing keystore private.

## Release

Current public version: **1.0.0**

For GitHub, tag the first release as:

```text
v1.0.0
```

## Credits

This project is a heavily modified continuation of **Unprocess by Reiland Eubank**.

It also builds on code and concepts from the **Android Camera Samples / Android Open Source Project**.

Thank you to:

- **Reiland Eubank and contributors** for the original Unprocess project and its minimal-processing camera concept.
- **The Android Open Source Project and Android Camera Samples contributors** for Camera2 examples and reference implementations.

This project is not affiliated with or endorsed by Google.

## License

Apache License 2.0.

See `LICENSE` and `THIRD_PARTY_NOTICES.md` for attribution and licensing details.
