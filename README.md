# c3lingo audio

Native app for listening to c3lingo audio channels (hall floor audio and live
interpretation) distributed through [Galène](https://galene.org/). Runs on iOS, iPadOS,
macOS and visionOS; an Android version is planned, aiming for feature parity
(tracked in [`docs/parity.md`](docs/parity.md)).

The app shows the event's web frontend
([listen-frontend](https://codeberg.org/c3lingo/listen-frontend)), loaded from the
server, so branding and channel layout change with the server and need no app
update. Playback is done natively:

- audio keeps playing in the background and with the screen locked
- lock screen / Control Center controls and Now Playing info
- AirPlay and Bluetooth output selection
- automatic reconnect with backoff, after network changes and after interruptions
  such as phone calls
- the microphone is never used, so there is no permission prompt

## Repository layout

```
apple/     Xcode project for iOS, iPadOS, macOS and visionOS
shared/    used by every app
  FrontendBridge.js   injected into the frontend, see below
  icon/               logo and the script that renders all app icons
docs/      notes that apply to all apps
```

## How it works

```
┌──────────── WKWebView ─────────────┐        ┌──────────── native ─────────────┐
│ listen-frontend (from the server)  │        │ Player       state, reconnects, │
│   c3lingoNative.play(), stop() …   │ ─────▶ │              Now Playing        │
│                                    │ bridge │ GaleneSession  WebSocket + one  │
│ FrontendBridge.js (injected)       │ ◀───── │              peer connection    │
│   c3lingo:state events             │ state  │              per stream         │
└────────────────────────────────────┘        │ PlayerBar    native controls    │
                                              └─────────────────────────────────┘
```

In the Apple app (`apple/c3lingo-audio/`):

- `Frontend.swift` hosts the web view, injects `shared/FrontendBridge.js` at document start
  and keeps the page in sync with the player.
- `GaleneSession.swift` is a receive-only client for the Galène protocol (version 2):
  handshake, join, request audio, answer offers, trickle ICE both ways, ping/pong
  keepalive. TURN servers from the server's `rtcConfiguration` are used.
- `AudioEngine.swift` sets up WebRTC (LiveKit's build, `LiveKitWebRTC`, which also
  covers macOS, tvOS and visionOS) with the AVAudioEngine audio device module and
  voice processing disabled, so the audio session can use the `.playback` category.
- `Player.swift` keeps the selected channel playing and drives the lock screen controls.

## Bridge between frontend and app

The app implements version 2 of the frontend's native bridge protocol, documented in
listen-frontend's
[`docs/playback-api.md`](https://codeberg.org/c3lingo/listen-frontend/src/branch/main/docs/playback-api.md).
In short:

- The page declares `<meta name="c3lingo-native-bridge" content="2">`. Only then does
  it see `window.c3lingoNative`; any other page is left alone and plays audio itself.
- The page calls `ready()`, `play(channel)`, `stop()` and `setMuted(muted)`.
- The app answers `ready()` and reports every state change, whatever caused it, as a
  `c3lingo:state` event with the full state `{state, channel, muted, error}`.

The app's player states map onto the protocol like this:

| app | `state` | notes |
|---|---|---|
| idle | `stopped` | `channel` is `null` |
| paused (e.g. from the lock screen) | `stopped` | `channel` stays set |
| connecting, reconnecting | `connecting` | `error` explains a reconnect |
| joined, nobody sending | `connecting` | `error` is "Waiting for audio" |
| playing | `playing` | |

## Icons

All icons are rendered from `shared/icon/c3lingo-logo.svg`, a copy of the logo in
[c3lingo-design](https://codeberg.org/c3lingo/c3lingo-design). After changing it, run
`shared/icon/render.sh` (needs `brew install librsvg imagemagick`).

## Server URL

The app uses `https://audio.c3lingo.org/` by default. For testing:

- iOS, iPadOS, visionOS: Settings → Apps → c3lingo → Server URL
- macOS: c3lingo → Settings… (⌘,)
- any platform, from Xcode or the command line: `-serverURL http://localhost:8000/`

Leave it empty to go back to the default. Plain `http` is allowed for local network
addresses only.

For testing playback without tapping through the UI, `-autoplay test` selects a channel
(here `test`) once the page has loaded, exactly as if it had been clicked.

## Building

Open `apple/c3lingo-audio.xcodeproj` in Xcode 27. The WebRTC framework comes in through Swift
Package Manager from `https://github.com/livekit/webrtc-xcframework`.

Minimum OS versions are iOS/iPadOS 15, macOS 12 and visionOS 26. iOS 15 and macOS 12
are the oldest deployment targets Xcode 27 supports; LiveKit's WebRTC build only ships
visionOS 26 and later.

tvOS and watchOS aren't supported, since there is no WKWebView on those platforms.
