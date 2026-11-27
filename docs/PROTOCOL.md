# CastPro-Zero Protocol v1 / v2

## Roles
The TCL TV is always the TCP control server, UDP video/audio receiver, and QR display. The OnePlus phone is always the QR scanner / control client / video+audio sender. LAN discovery, QR pairing, and manual IP entry converge on the same TV endpoint and work strictly on an isolated LAN or phone hotspot.

## Session Identity & Security
- One random **non-zero** 64-bit `SessionId` is created per session (`SessionIds.newSessionId()`) and validated across TCP and UDP headers.
- **Zero cleartext pairing token on wire:**
  - LAN UDP broadcast beacons (`CPZ0-LAN`, port `45672`) carry endpoint identity (`version`, `ipAddress`, `tcpPort`, `udpPort`, `deviceName`) only — **never** the secret `pairingToken`.
  - During `HELLO` / `HELLO_ACK` / `PAIR_CONFIRM`, client and server exchange 16-byte random nonces and prove knowledge of `pairingToken` via constant-time `HMAC-SHA256("CPZ0-PAIR-AUTHv2" || sessionId || clientNonce || serverNonce)`.
  - Ephemeral ECDH (`secp256r1` / P-256) `KEY_EXCHANGE` derives independent 256-bit `controlKey`, `videoKey`, and `audioKey` via HKDF-SHA256.
  - All post-handshake TCP control frames, UDP video fragments, and UDP audio datagrams are encrypted and authenticated with `ChaCha20-Poly1305` AEAD (headers authenticated via AAD; deterministic nonces prevent IV reuse).

## TCP Control Header (24 bytes, big-endian)
| Offset | Bytes | Field |
|---:|---:|---|
| 0 | 4 | ASCII `CPZ0` |
| 4 | 1 | version (`1`) |
| 5 | 1 | message type |
| 6 | 2 | flags (`bit 0` = AEAD encrypted) |
| 8 | 4 | payload length (`0..65536`) |
| 12 | 4 | sequence (`uint32`) |
| 16 | 8 | `SessionId` |

Message IDs: `HELLO=1`, `HELLO_ACK=2`, `PAIR_CONFIRM=3`, `STREAM_CONFIG=16`, `VIDEO_CSD=17`, `PING=32`, `PONG=33`, `PLI=34`, `STOP=35`, `RTT_REPORT=36`, `AUDIO_CONFIG=37`, `FRAGMENT_NACK=38`, `HEALTH_FEEDBACK=39`, `KEY_EXCHANGE=40`, `ERROR=127`.

## UDP Video Header (32 bytes, big-endian, port `udpPort` default `45671`)
| Offset | Bytes | Field |
|---:|---:|---|
| 0 | 2 | ASCII `CZ` |
| 2 | 1 | version (`1`) |
| 3 | 1 | flags (`1=first`, `2=last`, `4=keyframe`) |
| 4 | 8 | `SessionId` |
| 12 | 4 | `frameId` (`uint32`) |
| 16 | 2 | fragment index |
| 18 | 2 | fragment count |
| 20 | 2 | wire payload length (`plaintext + 16B AEAD tag` when encrypted) |
| 22 | 2 | reserved |
| 24 | 8 | `captureTimestampNs` |

Datagram ceiling is `1360` bytes (`1312` B plaintext video when sealed + `16` B Poly1305 tag + `32` B header). Production `VideoReassembler` keeps up to `6` in-flight frames with `45 ms` deadline and issues coalesced `FRAGMENT_NACK` at `8 ms` for missing packets before falling back to `100 ms` coalesced `PLI`.

## UDP Audio Header (32 bytes, big-endian, port `udpPort + 2` default `45673`)
| Offset | Bytes | Field |
|---:|---:|---|
| 0 | 2 | ASCII `CA` |
| 2 | 1 | version (`1`) |
| 3 | 1 | flags (`bit 0` = AEAD encrypted) |
| 4 | 8 | `SessionId` |
| 12 | 8 | sequence (`uint64`) |
| 20 | 8 | `timestampNs` |
| 28 | 2 | wire payload length |
| 30 | 2 | reserved |

Carries `48 kHz` stereo 16-bit PCM chunks (`<= 1312` bytes plaintext when AEAD-sealed, matching `AudioPlaybackCapture` → `OrderedAudioPlaybackBuffer` + `AvSyncController`).

## CSD & Decode Gate
AVC requires Annex-B SPS (`NAL type 7`) and PPS (`8`). HEVC requires VPS/SPS/PPS (`32/33/34`). No P-frame enters `MediaCodec` until validated CSD has been queued onto the decoder input thread and an IDR (`NAL 5` / `19..21`) passes `DecodeGate`; packet loss, overflow, or surface reset returns `DecodeGate` to `AWAITING_IDR`.

## TV-Origin Pairing QR Payload
Offline QR payload (`PairingPayloadCodec`) is URL-safe Base64 without padding of seven UTF-8 newline-separated fields: `CPZ0`, `version`, numeric TV IP, TCP port, UDP port, pairing token, expiry epoch seconds.
