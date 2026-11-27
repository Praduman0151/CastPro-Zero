# CastPro Zero

Low-latency, offline-first Android-to-Android-TV screen & audio casting engine built for sustained `1080p @ 60 FPS` hardware-accelerated mirroring over local LAN/5 GHz Wi-Fi.

## Architecture
- **Phone (`app-phone`, minSdk 34):** Captures screen (`MediaProjection` + `VirtualDisplay`) and device audio playback (`AudioPlaybackCapture`) using hardware `MediaCodec` (`H.264` Low-Latency CBR with IDR header prepending), paces UDP bursts, handles TCP control/NACK retransmissions, and adapts bitrate/resolution under thermal or network pressure.
- **TV (`app-tv`, minSdk 28):** Runs TCP control server + UDP video/audio receivers (`OMX`/`Codec2` hardware `H.264` decoder -> direct `Surface` render, `AudioTrack` low-latency playback with clock-drift/A-V sync), `OnFrameRenderedListener` Vsync FPS telemetry, and on-screen pairing QR + visible 6-char pairing verification code.
- **Protocol (`core-protocol`):** Ephemeral ECDH P-256 + HKDF-SHA256 authenticated handshake (HMAC-SHA256 pairing proof — pairing secret never sent in cleartext), optional/default ChaCha20-Poly1305 AEAD packet encryption for control, video fragments, and audio, plus selective `FRAGMENT_NACK` recovery and `HEALTH_FEEDBACK` telemetry.

## Docs
- [Protocol Specification (v1 & v2 AEAD)](docs/PROTOCOL.md)
- [Build Guide](docs/BUILD.md)
- [Milestones](docs/MILESTONES.md)

## Quick Build & Verify
```bash
./gradlew :core-protocol:test :app-phone:testDebugUnitTest :app-tv:testDebugUnitTest
./gradlew :app-phone:assembleDebug :app-tv:assembleDebug
```
