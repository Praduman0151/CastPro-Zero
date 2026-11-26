# Milestone 1B — live offline video casting

This branch adds the private IPv4 allow-list, pairing address enforcement, network pinning helpers, encoder format policy, and offline QR dependency. Runtime components are being added in this milestone.

## Offline boundary
- Runtime endpoints accept only numeric RFC1918 or IPv4 link-local addresses.
- No analytics, cloud SDK, Firebase, ML Kit, or Play Services runtime dependency is introduced.
- ZXing is bundled locally for QR encode/decode; CameraX is used for camera frames.

## Device validation
CI builds and unit tests do not establish 1080p60 performance or device compatibility. OnePlus Nord 5 and TCL Android TV hardware validation is still required before Milestone 2.
