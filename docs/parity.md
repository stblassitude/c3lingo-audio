# Feature parity

What each app does, so the apps stay in step. When a feature lands in one app, add it
here and note what's missing in the others.

| Feature | Apple | Android |
|---|---|---|
| **Frontend** | | |
| Loads listen-frontend from the server, bridge protocol version 2 (`shared/FrontendBridge.js`) | ✓ | – |
| Links leaving the frontend open in the browser | ✓ | – |
| Pull to refresh | ✓ (iOS) | – |
| Splash screen with logo, spinner and "Loading…" until the page has loaded | ✓ | – |
| Error screen with retry and a way to change the server | ✓ | – |
| **Playback** | | |
| Receive-only Galène client (protocol version 2), TURN from `rtcConfiguration` | ✓ | – |
| Plays in the background and with the screen locked | ✓ | – |
| Lock screen controls and Now Playing info | ✓ | – |
| Native player bar with mute | ✓ | – |
| Audio output selection (AirPlay, Bluetooth) | ✓ | – |
| Reconnect with backoff, after network changes and interruptions | ✓ | – |
| "Waiting for audio" while joined with nobody sending | ✓ | – |
| Never uses the microphone, no permission prompt | ✓ | – |
| **Settings and testing** | | |
| Custom server URL, empty for the default | ✓ | – |
| `serverURL` and `autoplay` launch arguments | ✓ | – |
| **Branding** | | |
| App icon from `shared/icon` | ✓ | – |
