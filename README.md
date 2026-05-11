# RTSP Master Player

A production-ready Android RTSP receiver and stream forwarder specifically designed for MediaTek devices (like Redmi 14C) and robust multi-device compatibility.

## Features
- **Multi-Tiered Decoding**: 
  - **LibVLC (Tier 1)**: Software decoding fallback for 100% color accuracy.
  - **ExoPlayer (Tier 2)**: Standard AndroidX Media3 implementation.
  - **Raw MediaCodec (Tier 3)**: Ultra-low latency raw protocol handler.
- **MediaTek Deep Fixes**: Implements 8 low-level workarounds to eliminate green screens.
- **GPU Color Correction**: OpenGL ES shader layer to fix YUV issues on the fly.
- **Background Streaming**: Foreground service ensures the stream doesn't die when app is minimized.
- **Stream Forwarding**: Mirror raw RTP packets to any destination IP/Port.
- **Intelligent Recovery**: Auto-detects green frames and health drops to switch strategies automatically.

## How to get the APK
Since this project is set up with GitHub Actions, you can get the APK without needing Android Studio:
1. Go to the **Actions** tab in this repository.
2. Click on the latest **Build APK** workflow run.
3. Scroll down to **Artifacts** and download `app-debug`.

## For Developers
If you want to modify the code:
- Minimum SDK: 26 (Android 8.0)
- Target SDK: 35 (Android 15)
- Java 17 / Kotlin 1.9
- Architecture: `arm64-v8a` focused for modern performance.

## License
MIT
