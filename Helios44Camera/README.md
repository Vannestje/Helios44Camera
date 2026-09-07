# Helios 44-2 Camera — Android prototype

Native Android camera prototype tuned for the visual character of a Helios 44-2 58mm f/2.

## Milestone 1

- CameraX live preview
- Android 13+ AGSL / RuntimeShader GPU effect
- Helios-inspired tangential edge smear rather than a simple twirl distortion
- Adjustable swirl, sharp-zone size, bokeh smear and vignette
- Tap the subject to move both autofocus and the effect's sharp center
- Front/back camera switching
- Processed JPEG capture saved to `Pictures/Helios44`
- CameraX video recording to `Movies/Helios44`

### Important video status

The effect is visible during video preview, but milestone 1 records the clean CameraX video stream. The next milestone is a dual-output EGL/MediaCodec path so the same shader is baked into the encoded video frames in real time.

## Device target

Designed for Pixel 7 Pro and other Android 13+ devices. `minSdk=33` is intentional because the live effect uses `RuntimeShader` (AGSL).

## Build

Open in a current Android Studio and build the `app` module, or push this project to GitHub and run the included GitHub Actions workflow. The workflow uploads `app-debug.apk` as a build artifact.
