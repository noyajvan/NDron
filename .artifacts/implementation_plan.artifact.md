# Optimization of Video Streaming (H.264 Hardware Encoding) & Oracle Cloud Traffic Monitor

## Goal
Implement ultra-low latency, low-CPU, low-bandwidth video streaming using hardware H.264 MediaCodec encoding (UDP RTP) and add real-time traffic monitoring tracking Oracle Cloud Free Tier limits (traffic per minute, session total, and quota percentage) displayed directly on the phone interface and web dashboard.

## User Review Required

> [!IMPORTANT]
> - **Hardware H.264 Encoder**: Replaces/augments software MJPEG compression with hardware MediaCodec H.264 encoding to reduce CPU heating and network bandwidth by 5–10x.
> - **Oracle Cloud Traffic Monitor**: Tracks transmitted bytes per minute and calculates consumption against the Oracle Always Free 10 TB/month egress limit.

## Proposed Changes

### Telemetry Bridge Service & Streamer
#### [MODIFY] [TelemetryBridgeService.kt](file:///C:/Users/lenovo/AndroidStudioProjects/DRN_kotlin/app/src/main/java/com/example/drn_kotlin/TelemetryBridgeService.kt)
- Initialize and manage `H264RtpStreamer` alongside `MjpegServer`.
- Add traffic accumulator to measure network bytes transmitted per second/minute.
- Calculate Oracle Free Tier usage (10 TB/month limit reference).
- Broadcast traffic statistics (`TRAFFIC_UPDATE`) to `MainActivity`.

### UI & Dashboard
#### [MODIFY] [MainActivity.kt](file:///C:/Users/lenovo/AndroidStudioProjects/DRN_kotlin/app/src/main/java/com/example/drn_kotlin/MainActivity.kt)
- Register broadcast receiver for traffic stats.
- Update `videoStats` / UI labels to display current traffic rate (`MB/min`), session total (`MB`), and Oracle Free Tier usage percentage.

#### [MODIFY] [MjpegServer.kt](file:///C:/Users/lenovo/AndroidStudioProjects/DRN_kotlin/app/src/main/java/com/example/drn_kotlin/MjpegServer.kt)
- Add traffic statistics to web dashboard (`/` and `/health`) so remote GCS operators can monitor Oracle bandwidth usage.

## Verification Plan

### Automated Tests
- Build test using `gradle_build("app:assembleDebug")`.

### Manual Verification
- Verify app compiles and runs on connected device.
- Check that traffic stats appear on phone screen and web dashboard.
