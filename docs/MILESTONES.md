# Milestones

## 1A — Foundation + protocol + green CI
Gradle multi-module build, protocol library, serializer/framing tests, UDP fragmentation/reassembly, Annex-B parameter-set validation/decode gate, TV-to-phone pairing payload, minimal phone and TV Compose shells, and CI publishing both debug APK artifacts.

## 1B — Live video
TV TCP server / UDP receiver and QR presentation; phone QR scanning, LAN broadcast and manual IP discovery; MediaProjection capture, hardware H.264 encoder, UDP transport, SurfaceView decoder/rendering, and measured FPS/RTT/drop telemetry. No implementation in 1A.

## 2 — Audio + Wi-Fi Direct
48 kHz stereo PCM AudioPlaybackCapture / AudioTrack path, audio-video sync, and Wi-Fi Direct 5 GHz best-effort transport.

## 3 — Advanced gaming
Adaptive bitrate, opt-in HEVC, 120 FPS profile where both endpoints support it, and extended thermal / stability testing.
